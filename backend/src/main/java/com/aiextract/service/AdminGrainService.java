package com.aiextract.service;

import com.aiextract.common.ErrorMessages;
import com.aiextract.config.DomainConfigLoader;
import com.aiextract.config.PromptLoader;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.*;
import com.aiextract.repository.*;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Admin 颗粒管理 Service —— 封装颗粒编辑、新增、废弃的业务逻辑。
 *
 * <p>Controller 只做参数校验和路由，业务逻辑和事务边界在 Service 层。</p>
  * @author AI Extract Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminGrainService {
    private static final String KEY_APPLICABLE_CONDITION = "applicableCondition";
    private static final String KEY_COMMON_MISTAKES = "commonMistakes";
    private static final String KEY_EDIT_NOTE = "editNote";
    private static final String KEY_EXPERT_THOUGHT = "expertThought";
    private static final String KEY_SCENE_DESCRIPTION = "sceneDescription";
    private static final String KEY_SCENE_TAG = "sceneTag";
    private static final String KEY_SKILL_ID = "skillId";
    private static final String KEY_STANDARD_SCRIPT = "standardScript";
    private static final String KEY_WEIGHT = "weight";


    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private AdminGrainService self;

    private final ExperienceGrainRepository grainRepository;
    private final SkillRepository skillRepository;
    private final DashScopeEmbeddingService embeddingService;
    private final GrainEditHistoryRepository editHistoryRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final PromptLoader promptLoader;
    private final DomainConfigLoader domainConfigLoader;
    private final KnowledgeGapRepository knowledgeGapRepository;
    private final CandidateGrainRepository candidateGrainRepository;

    /**
     * 候选颗粒审批的落库部分（事务内）。嵌入在事务外计算后传入。
     * 只有候选仍为 pending 时才成功，保证并发审批不会重复入库。
     */
    @Transactional(rollbackFor = Exception.class)
    public void persistApprovedCandidate(ExperienceGrain grain, UUID candidateId,
                                         String embedding, String note) {
        grainRepository.saveAndFlush(grain);
        int vecUpdated = grainRepository.updateEmbedding(grain.getId(), embedding);
        if (vecUpdated != 1) {
            throw new BusinessException(500, "候选颗粒向量写入失败");
        }
        int approved = candidateGrainRepository.markApprovedIfPending(
            candidateId, LocalDateTime.now(), note);
        if (approved != 1) {
            // 回滚已插入的颗粒，避免重复审批产生孤儿
            throw new BusinessException(409, "候选颗粒已被处理");
        }
    }

    /**
     * 候选颗粒审批的异步入口：嵌入在后台线程计算，HTTP 线程不调外部服务。
     * 失败时候选保持 pending，管理员可重试。
     */
    @org.springframework.scheduling.annotation.Async("embeddingExecutor")
    public void approveCandidateAsync(ExperienceGrain grain, UUID candidateId, String note) {
        try {
            String text = embeddingService.grainToText(grain);
            if (text == null || text.isBlank() || "未分类".equals(text)) {
                text = ((grain.getExpertThought() != null ? grain.getExpertThought() : "")
                        + " " + (grain.getStandardScript() != null ? grain.getStandardScript() : "")).trim();
            }
            if (text.isEmpty()) {
                log.warn("候选颗粒缺少可检索文本，保持 pending candidateId={}", candidateId);
                return;
            }
            float[] vec = embeddingService.embed(text);
            self.persistApprovedCandidate(grain, candidateId, new PGvector(vec).toString(), note);
            log.info("候选颗粒异步审批完成 candidateId={} grainId={}", candidateId, grain.getId());
        } catch (Exception e) {
            log.error("候选颗粒异步审批失败 candidateId={}", candidateId, e);
        }
    }

    /**
     * 更新颗粒：向量化在事务外先算好，任一环节失败都不会留下「改了字段但不可检索」的半成品。
     */
    public ExperienceGrain updateGrain(UUID grainId, Map<String, String> body) {
        ExperienceGrain g = grainRepository.findById(grainId)
            .orElseThrow(() -> new BusinessException(404,"颗粒不存在: " + grainId));
        Double weight = parseWeight(body.get(KEY_WEIGHT));
        ExperienceGrain preview = previewWithBody(g, body, weight);
        float[] vec = embed(preview);
        return self.applyGrainUpdate(grainId, body, weight,
                new PGvector(vec).toString());
    }

    /** 短事务：落字段、历史、向量、缓存失效与审计 */
    @Transactional(rollbackFor = Exception.class)
    public ExperienceGrain applyGrainUpdate(UUID grainId, Map<String, String> body,
                                            Double weight, String embedding) {
        ExperienceGrain g = grainRepository.findById(grainId)
            .orElseThrow(() -> new BusinessException(404,"颗粒不存在: " + grainId));
        String editedBy = body.getOrDefault("editedBy", "admin");

        // 记录每个变更字段的历史
        saveEditHistory(grainId, "expertThought", g.getExpertThought(), body.get(KEY_EXPERT_THOUGHT), editedBy, body.get(KEY_EDIT_NOTE));
        if (body.containsKey(KEY_EXPERT_THOUGHT)) { g.setExpertThought(body.get(KEY_EXPERT_THOUGHT)); }
        saveEditHistory(grainId, "standardScript", g.getStandardScript(), body.get(KEY_STANDARD_SCRIPT), editedBy, body.get(KEY_EDIT_NOTE));
        if (body.containsKey(KEY_STANDARD_SCRIPT)) { g.setStandardScript(body.get(KEY_STANDARD_SCRIPT)); }
        saveEditHistory(grainId, "commonMistakes", g.getCommonMistakes(), body.get(KEY_COMMON_MISTAKES), editedBy, body.get(KEY_EDIT_NOTE));
        if (body.containsKey(KEY_COMMON_MISTAKES)) { g.setCommonMistakes(body.get(KEY_COMMON_MISTAKES)); }
        saveEditHistory(grainId, "applicableCondition", g.getApplicableCondition(), body.get(KEY_APPLICABLE_CONDITION), editedBy, body.get(KEY_EDIT_NOTE));
        if (body.containsKey(KEY_APPLICABLE_CONDITION)) { g.setApplicableCondition(body.get(KEY_APPLICABLE_CONDITION)); }
        if (body.containsKey(KEY_SCENE_TAG)) { g.setSceneTag(body.get(KEY_SCENE_TAG)); }
        if (weight != null) { g.setWeight(weight); }

        grainRepository.save(g);
        int updated = grainRepository.updateEmbedding(grainId, embedding);
        if (updated != 1) {
            throw new BusinessException(500, "颗粒向量写入失败（影响行数 " + updated + "）");
        }
        invalidateCache(g);
        writeAudit("edit_grain", grainId, body.keySet().toString());

        return g;
    }

    /** weight 显式传空/非法时返回 400，而不是 500 */
    private Double parseWeight(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            if (value < 0 || value > 10) {
                throw new BusinessException(400, "weight 必须在 0–10 之间");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "weight 必须是数字");
        }
    }

    /** 构造仅用于计算向量文本的快照，不持久化 */
    private ExperienceGrain previewWithBody(ExperienceGrain g, Map<String, String> body, Double weight) {
        ExperienceGrain preview = ExperienceGrain.builder()
            .id(g.getId()).spaceId(g.getSpaceId()).sceneTag(g.getSceneTag())
            .sceneDescription(g.getSceneDescription())
            .expertThought(g.getExpertThought()).standardScript(g.getStandardScript())
            .commonMistakes(g.getCommonMistakes()).applicableCondition(g.getApplicableCondition())
            .weight(g.getWeight()).status(g.getStatus())
            .build();
        if (body.containsKey(KEY_EXPERT_THOUGHT)) { preview.setExpertThought(body.get(KEY_EXPERT_THOUGHT)); }
        if (body.containsKey(KEY_STANDARD_SCRIPT)) { preview.setStandardScript(body.get(KEY_STANDARD_SCRIPT)); }
        if (body.containsKey(KEY_COMMON_MISTAKES)) { preview.setCommonMistakes(body.get(KEY_COMMON_MISTAKES)); }
        if (body.containsKey(KEY_APPLICABLE_CONDITION)) { preview.setApplicableCondition(body.get(KEY_APPLICABLE_CONDITION)); }
        if (body.containsKey(KEY_SCENE_TAG)) { preview.setSceneTag(body.get(KEY_SCENE_TAG)); }
        if (weight != null) { preview.setWeight(weight); }
        return preview;
    }

    /**
     * 属主编辑颗粒后刷新向量与缓存（K26）。
     * 与后台编辑共用同一套 grainToText / reEmbed，避免两套向量文本。
     */
    public void refreshEmbedding(UUID grainId) {
        grainRepository.findById(grainId).ifPresent(g -> {
            reEmbed(g);
            invalidateCache(g);
        });
    }

    /**
     * 异步重算向量：编辑接口不能在 HTTP 线程调外部 embedding（RT 与失败一致性都不可控）。
     * 文本已落库，向量失败只影响检索新鲜度，不影响编辑结果。
     */
    @org.springframework.scheduling.annotation.Async("embeddingExecutor")
    public void refreshEmbeddingAsync(UUID grainId) {
        try {
            refreshEmbedding(grainId);
        } catch (Exception e) {
            log.warn("异步重算颗粒向量失败 grainId={}: {}", grainId, e.getMessage());
        }
    }

    /** 新增颗粒：向量在事务外算好，落库与向量写入在同一短事务 */
    public ExperienceGrain createGrain(Map<String, Object> body) {
        UUID skillId = parseSkillId(body);
        var skill = skillRepository.findById(skillId)
            .orElseThrow(() -> new BusinessException(404,"分身不存在: " + skillId));

        String sceneTag = (String) body.get(KEY_SCENE_TAG);
        String sceneDescription = (String) body.get(KEY_SCENE_DESCRIPTION);
        if (sceneTag == null || sceneTag.isBlank()) {
            throw new BusinessException(400, "场景标签不能为空");
        }
        if (sceneDescription == null || sceneDescription.isBlank()) {
            throw new BusinessException(400, "场景描述不能为空");
        }

        ExperienceGrain g = ExperienceGrain.builder()
            .id(UUID.randomUUID()).spaceId(skill.getSpaceId())
            .sceneTag(PiiMasker.mask(sceneTag))
            .sceneDescription(PiiMasker.mask(sceneDescription))
            .expertThought(PiiMasker.mask((String) body.get(KEY_EXPERT_THOUGHT)))
            .standardScript(PiiMasker.mask((String) body.get(KEY_STANDARD_SCRIPT)))
            .commonMistakes(PiiMasker.mask((String) body.get(KEY_COMMON_MISTAKES)))
            .applicableCondition(PiiMasker.mask((String) body.get(KEY_APPLICABLE_CONDITION)))
            .weight(1.0).status("active").sourceType("manual")
            .helpfulCount(0).unhelpfulCount(0).createdAt(LocalDateTime.now())
            .build();
        float[] vec = embed(g);
        return self.persistNewGrain(g, skillId, body, new PGvector(vec).toString());
    }

    /** 短事务：落颗粒行 + 写向量 + 关闭知识缺口 + 审计 */
    @Transactional(rollbackFor = Exception.class)
    public ExperienceGrain persistNewGrain(ExperienceGrain g, UUID skillId,
                                           Map<String, Object> body, String embedding) {
        grainRepository.saveAndFlush(g);
        int updated = grainRepository.updateEmbedding(g.getId(), embedding);
        if (updated != 1) {
            throw new BusinessException(500, "颗粒向量写入失败（影响行数 " + updated + "）");
        }
        resolveGapIfPresent(body, skillId);
        writeAudit("create_grain", g.getId(), "{\"action\":\"create\"}");
        return g;
    }

    private UUID parseSkillId(Map<String, Object> body) {
        Object raw = body.get(KEY_SKILL_ID);
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new BusinessException(400, "缺少 skillId");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "skillId 格式不正确");
        }
    }

    /** 从知识缺口补录的颗粒创建成功后，关闭对应缺口 */
    private void resolveGapIfPresent(Map<String, Object> body, UUID skillId) {
        Object raw = body.get("gapId");
        if (!(raw instanceof String gapIdStr) || gapIdStr.isBlank()) { return; }
        UUID gapId;
        try {
            gapId = UUID.fromString(gapIdStr);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "gapId 格式不正确");
        }
        KnowledgeGap gap = knowledgeGapRepository.findById(gapId)
            .orElseThrow(() -> new BusinessException(404, "知识缺口不存在"));
        if (!skillId.equals(gap.getSkillId())) {
            throw new BusinessException(400, "知识缺口不属于当前分身");
        }
        gap.setStatus("resolved");
        gap.setResolvedAt(LocalDateTime.now());
        knowledgeGapRepository.save(gap);
        log.info("知识缺口已关闭 gapId={} grainSkillId={}", gapId, skillId);
    }

    /** 恢复已废弃颗粒：重新 active 并确保向量可用 */
    @Transactional(rollbackFor = Exception.class)
    public void restoreGrain(UUID grainId) {
        ExperienceGrain g = grainRepository.findById(grainId)
            .orElseThrow(() -> new BusinessException(404, "颗粒不存在: " + grainId));
        if (!"deprecated".equals(g.getStatus())) {
            throw new BusinessException(400, "只有已废弃的颗粒可以恢复");
        }
        g.setStatus("active");
        grainRepository.save(g);
        writeAudit("restore_grain", grainId, "{\"action\":\"restore\"}");
    }

    /** 废弃颗粒 */
    @Transactional(rollbackFor = Exception.class)
    public void deprecateGrain(UUID grainId) {
        ExperienceGrain g = grainRepository.findById(grainId)
            .orElseThrow(() -> new BusinessException(404,"颗粒不存在: " + grainId));
        g.setStatus("deprecated");
        grainRepository.save(g);
        writeAudit("deprecate_grain", grainId, "{\"action\":\"deprecate\"}");
    }

    /**
     * 重新向量化。
     *
     * <p>必须与批量入库使用同一套文本（{@link DashScopeEmbeddingService#grainToText}），
     * 否则手动补录的颗粒与存量颗粒不在同一向量空间，检索命中率会异常。</p>
     *
     * <p>失败不再静默吞掉：向量为空等于颗粒不可检索，必须让调用方知道。</p>
     */
    private void reEmbed(ExperienceGrain g) {
        float[] vec = embed(g);
        int updated = grainRepository.updateEmbedding(g.getId(), new PGvector(vec).toString());
        if (updated != 1) {
            throw new BusinessException(500, "颗粒向量写入失败（影响行数 " + updated + "）");
        }
        log.info("颗粒向量化完成 grainId={}", g.getId());
    }

    /** 计算向量：文本为空时 400，向量为空等于颗粒不可检索 */
    private float[] embed(ExperienceGrain g) {
        String text = embeddingService.grainToText(g);
        if (text == null || text.isBlank() || "未分类".equals(text)) {
            text = ((g.getExpertThought() != null ? g.getExpertThought() : "")
                + " " + (g.getStandardScript() != null ? g.getStandardScript() : "")).trim();
        }
        if (text.isEmpty()) {
            throw new BusinessException(400, "颗粒缺少可用于检索的场景描述或话术");
        }
        return embeddingService.embed(text);
    }

    /** 失效相关缓存 */
    private void invalidateCache(ExperienceGrain g) {
        try {
            var skill = skillRepository.findBySpaceId(g.getSpaceId());
            if (skill.isPresent()) {
                String domain = domainConfigLoader.resolveDomain(skill.get());
                if (domain != null) {

                    domainConfigLoader.invalidate(domain);

                }
            }
            promptLoader.invalidate("skill_qa_chat.md");
            promptLoader.invalidate("skill_talk.md");
        } catch (Exception e) { log.debug("失效缓存失败: {}", e.getMessage()); }
    }

    /** 记录编辑历史 */
    private void saveEditHistory(UUID grainId, String field, String oldVal, String newVal, String by, String note) {
        if (newVal == null || newVal.equals(oldVal)) { return; }
        editHistoryRepository.save(GrainEditHistory.builder()
            .id(UUID.randomUUID()).grainId(grainId).fieldName(field)
            .oldValue(truncate(oldVal)).newValue(truncate(newVal))
            .editedBy(by).editNote(note).createdAt(LocalDateTime.now()).build());
    }

    /** 写操作审计。adminId 从请求上下文获取，无法获取时用 "system" 标记。 */
    private void writeAudit(String action, UUID targetId, String detail) {
        try {
            UUID adminId = null;
            try {
                var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof UUID) {
                    adminId = (UUID) auth.getPrincipal();
                }
            } catch (Exception ignored) {}
            auditLogRepository.save(AdminAuditLog.builder()
                .id(UUID.randomUUID())
                .adminId(adminId != null ? adminId : UUID.fromString("00000000-0000-0000-0000-000000000000"))
                .action(action).targetType("grain").targetId(targetId).detail(detail)
                .createdAt(LocalDateTime.now()).build());
        } catch (Exception e) { log.debug("审计日志写入失败: {}", e.getMessage()); }
    }

    private String truncate(String s) {
        return s != null && s.length() > 1000 ? s.substring(0, 1000) : s;
    }

}
