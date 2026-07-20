package com.aiextract.service;

import com.aiextract.config.DomainConfig;
import com.aiextract.config.DomainConfigLoader;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.InterviewMessage;
import com.aiextract.model.InterviewSession;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillMaterial;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.InterviewMessageRepository;
import com.aiextract.repository.InterviewSessionRepository;
import com.aiextract.repository.SkillMaterialRepository;
import com.aiextract.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 访谈转录 → 经验颗粒 转换器。
 *
 * <p>核心设计：访谈转录作为虚拟 SkillMaterial 入库后，复用
 * MaterialCleaningScheduler.processMaterial() 统一管道
 * （清洗 → 颗粒落库 → 向量化 → 终态标记 → 报告标脏）。
 * 调度器扫描已排除 material_type='interview'，访谈素材由本类独占驱动，无竞争无需抢锁。</p>
 *
 * <p>去重：clean() 内部的 deduplicate() 使用 Jaccard 3-gram 与同 space
 * 全部存量颗粒对比（阈值 0.7），文件颗粒和访谈颗粒在同一去重池。</p>
 *
 * <p>失败重试：素材标 failed 后转录不丢（parsedContent 保留），
 * 管理端再次 forceComplete 会复用原素材行、用 interview_message 重拼的最新转录重跑。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-16
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewTranscriptExtractor {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewMessageRepository messageRepository;
    private final SkillRepository skillRepository;
    private final SkillMaterialRepository materialRepository;
    private final ExperienceGrainRepository grainRepository;
    private final MaterialCleaningService cleaningService;
    private final MaterialCleaningScheduler cleaningScheduler;
    private final DomainConfigLoader domainConfigLoader;

    /**
     * 从访谈转录文本中提取经验颗粒。
     *
     * <p>异步执行，不阻塞访谈完成的 SSE 线程。</p>
     *
     * <p>流程：
     * <ol>
     *   <li>加载 InterviewSession + 全部消息</li>
     *   <li>拼接完整转录文本，按角色标注</li>
     *   <li>幂等/重试守卫：已提取过则跳过；上次 failed 则复用原行重试</li>
     *   <li>创建/复用虚拟 SkillMaterial（id 由 persist 生成回填）</li>
     *   <li>调用 MaterialCleaningScheduler.processMaterial() 统一管道</li>
     *   <li>标记颗粒来源 source_type="interview"</li>
     *   <li>报告由管道内标脏，2 分钟内合并生成</li>
     * </ol>
     *
     * @param sessionId 已完成访谈的会话ID
     */
    @Async("embeddingExecutor")
    @SuppressWarnings("PMD.MethodTooLongRule")
    public void extractFromInterview(UUID sessionId) {
        log.info("开始从访谈提取颗粒, sessionId={}", sessionId);

        // 1. 加载访谈数据
        InterviewSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.error("访谈会话不存在, sessionId={}", sessionId);
            return;
        }

        List<InterviewMessage> messages = messageRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (messages.isEmpty()) {
            log.warn("访谈无消息, sessionId={}", sessionId);
            return;
        }

        // 2. 解析 domain（提前校验 + 预加载 domain config）
        String domain = session.getDomain();
        if (domain == null || domain.isBlank()) {
            log.error("InterviewSession.domain 为空, sessionId={}", sessionId);
            return;
        }
        // 预加载 domain 配置的 role_label，避免在 stream 内每条消息加载一次
        String roleLabel = loadRoleLabel(domain);

        // 3. 按角色标注，区分萃取师追问和受访者回答
        String transcript = messages.stream()
                .map(m -> String.format("[%s]: %s",
                        roleForMessage(m.getRole(), roleLabel),
                        m.getContent()))
                .collect(Collectors.joining("\n\n"));

        // 4. 找到 space 的 Skill
        Skill skill = skillRepository.findBySpaceId(session.getSpaceId()).orElse(null);
        if (skill == null) {
            log.warn("空间无Skill, spaceId={}", session.getSpaceId());
            return;
        }

        // 5. 幂等/重试守卫：同一会话只对应一个访谈素材（forceComplete 可重复触发）
        String virtualUrl = "interview://" + sessionId;
        SkillMaterial material = materialRepository.findByFileUrl(virtualUrl).orElse(null);
        boolean retryable = material != null
                && ("failed".equals(material.getStatus()) || "rejected".equals(material.getStatus()));
        if (material != null && !retryable) {
            log.warn("访谈素材已提取过，跳过, sessionId={}, materialId={}, status={}",
                    sessionId, material.getId(), material.getStatus());
            return;
        }
        if (retryable) {
            // 上次 failed/rejected → 复用原行重试：用最新重拼的转录覆盖后重跑管道
            material.setParsedContent(transcript);
            material.setStatus("uploaded");
            material.setAnalysisNotes(null);
            material.setRetryCount(0);
            material = materialRepository.save(material);
            log.info("访谈素材失败重试, sessionId={}, materialId={}", sessionId, material.getId());
        } else {
            // 新建虚拟素材 — 不设 id，由 @GeneratedValue 在 persist 时生成并回填
            material = materialRepository.save(SkillMaterial.builder()
                    .skillId(skill.getId())
                    .fileName("【访谈】" + session.getTopic())
                    .fileType("text/plain")
                    .fileUrl(virtualUrl)
                    .parsedContent(transcript)
                    // clean() 只读这个字段
                    .uploadedBy(UUID.fromString("00000000-0000-0000-0000-000000000000"))
                    .materialType("interview")
                    // 调度器扫描已排除该类型，由本类独占驱动
                    .status("uploaded")
                    .createdAt(LocalDateTime.now())
                    .build());
        }
        final UUID materialId = material.getId();
        // DB 真实 id，后续统一使用

        // 6. 统一管道：清洗 → 颗粒落库 → 向量化 → 终态标记 → 报告标脏
        try {
            int grainCount = cleaningScheduler.processMaterial(materialId);
            if (grainCount == 0) {
                log.warn("访谈素材未产出颗粒（准入拒绝或内容不足）, sessionId={}, materialId={}",
                        sessionId, materialId);
                return;
            }

            // 7. 标记颗粒来源为 interview
            List<ExperienceGrain> grains =
                    grainRepository.findBySourceMaterialId(materialId);
            for (ExperienceGrain g : grains) {
                g.setSourceType("interview");
                g.setSourceInterviewId(sessionId);
            }
            grainRepository.saveAll(grains);

            log.info("访谈颗粒提取完成, sessionId={}, grainCount={}",
                    sessionId, grains.size());
            // 报告已由 processMaterial 内部标脏，2 分钟内合并生成

        } catch (Exception e) {
            log.error("访谈颗粒提取失败, sessionId={}, materialId={}",
                    sessionId, materialId, e);
            // 短事务按 DB 真实 id 标 failed（REQUIRES_NEW 跨 Bean 代理生效），
            // 转录不丢，可经 forceComplete 重试
            try {
                cleaningService.updateMaterialStatus(materialId, "failed",
                        "访谈转录清洗失败: " + e.getMessage());
            } catch (Exception statusEx) {
                log.error("失败状态落库失败, materialId={}", materialId, statusEx);
            }
        }
    }

    /**
     * 预加载 domain 配置的 role_label，避免在 stream 内每消息加载一次。
     */
    private String loadRoleLabel(String domain) {
        try {
            DomainConfig dc = domainConfigLoader.load(domain);
            if (dc != null && dc.getDomain() != null
                    && dc.getDomain().getRoleLabel() != null) {
                return dc.getDomain().getRoleLabel();
            }
        } catch (Exception ignored) {
            // domain 配置加载失败，使用默认标签
        }
        return "受访者";
    }

    private static final String ROLE_AI = "ai";
    private static final String ROLE_SYSTEM = "system";
    private static final String LABEL_EXTRACTOR = "萃取师";
    private static final String LABEL_SYSTEM = "系统";

    /** 按角色返回对应的显示标签 */
    private String roleForMessage(String role, String defaultRoleLabel) {
        if (ROLE_AI.equals(role)) { return LABEL_EXTRACTOR; }
        if (ROLE_SYSTEM.equals(role)) { return LABEL_SYSTEM; }
        return defaultRoleLabel;
    }
}
