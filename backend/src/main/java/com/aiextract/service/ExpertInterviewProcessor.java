package com.aiextract.service;

import com.aiextract.model.ExpertSkill;
import com.aiextract.model.InterviewMessage;
import com.aiextract.model.InterviewSession;
import com.aiextract.repository.ExpertSkillRepository;
import com.aiextract.repository.InterviewMessageRepository;
import com.aiextract.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 萃取师元访谈 → ExpertSkill 处理器。
 *
 * <p>元访谈完成后，创建 ExpertSkill（status="pending"），
 * ExpertAnalysisScheduler 在 30s 内自动扫描并处理：
 * analyzeMaterials → extractGrains → 管理员审核 → activate。</p>
 *
 * <p>复用现有的 ExpertAnalysisScheduler + ExpertService 全流程，
 * 唯一差异：ExpertSkill.sourceType = "interview"（区别于文件上传的 "document"）。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-16
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpertInterviewProcessor {

    private final InterviewSessionRepository sessionRepository;
    private final InterviewMessageRepository messageRepository;
    private final ExpertSkillRepository expertSkillRepository;
    private final MaterialNoiseCleaner noiseCleaner;
    private final BusinessNoiseFilter businessFilter;
    private final TextNormalizer normalizer;

    /**
     * 从元访谈创建 ExpertSkill 并送入分析管道。
     *
     * <p>异步执行，ExpertAnalysisScheduler 下一次扫描（30s内）自动拾取。</p>
     *
     * @param sessionId 已完成元访谈的会话ID
     */
    @Async("embeddingExecutor")
    public void processExpertInterview(UUID sessionId) {
        log.info("开始处理萃取师元访谈, sessionId={}", sessionId);

        InterviewSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.error("元访谈会话不存在, sessionId={}", sessionId);
            return;
        }

        List<InterviewMessage> messages = messageRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (messages.isEmpty()) {
            log.warn("元访谈无消息, sessionId={}", sessionId);
            return;
        }

        // 拼接转录文本（AI萃取师 ↔ 专家 的对话）
        String transcript = messages.stream()
                .map(m -> String.format("[%s]: %s",
                        "ai".equals(m.getRole()) ? "AI萃取师" : "萃取师专家",
                        m.getContent()))
                .collect(Collectors.joining("\n\n"));

        // P2-10: 元访谈转录走规则清洗（Layer 1-3），对齐文件素材的清洗标准
        String cleaned = noiseCleaner.cleanFormatNoise(transcript, "dialogue");
        cleaned = businessFilter.filterBusinessNoise(cleaned,
            session.getDomain() != null ? session.getDomain() : "sales.b2b_enterprise");
        cleaned = normalizer.normalize(cleaned);
        log.info("元访谈转录清洗 sessionId={}: {}字→{}字", sessionId, transcript.length(), cleaned.length());

        String domain = session.getDomain();
        if (domain == null || domain.isBlank()) {
            log.error("元访谈 session.domain 为空, sessionId={}", sessionId);
            return;
        }

        // 创建 ExpertSkill，status="pending"
        // ExpertAnalysisScheduler 30s 内自动扫描并处理
        ExpertSkill expert = ExpertSkill.builder()
                .id(UUID.randomUUID())
                .name("萃取师-" + session.getTopic())
                .description("从元访谈萃取: " + session.getTopic())
                .sourceType("interview")
                // 区别于文件上传的 "document"
                .sourceSessionId(sessionId)
                // 可追溯到具体会话
                .sourceContent(cleaned)
                // P2-10: 清洗后的转录文本，scheduler 会读取
                .domain(domain)
                // 继承访谈的领域
                .status("pending")
                // scheduler 入口状态
                .grainCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        expertSkillRepository.save(expert);
        log.info("元访谈 ExpertSkill 创建成功, expertId={}, sessionId={}, domain={}",
                expert.getId(), sessionId, domain);
    }
}
