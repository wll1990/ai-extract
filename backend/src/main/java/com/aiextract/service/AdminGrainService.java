package com.aiextract.service;

import com.aiextract.common.ErrorMessages;
import com.aiextract.config.DomainConfigLoader;
import com.aiextract.config.PromptLoader;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.*;
import com.aiextract.repository.*;
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


    private final ExperienceGrainRepository grainRepository;
    private final SkillRepository skillRepository;
    private final DashScopeEmbeddingService embeddingService;
    private final GrainEditHistoryRepository editHistoryRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final PromptLoader promptLoader;
    private final DomainConfigLoader domainConfigLoader;

    /**
     * 更新颗粒字段 + 记录编辑历史 + 重新向量化 + 失效缓存 + 写审计日志。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExperienceGrain updateGrain(UUID grainId, Map<String, String> body) {
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
        if (body.containsKey(KEY_WEIGHT)) { g.setWeight(Double.valueOf(body.get(KEY_WEIGHT))); }

        grainRepository.save(g);
        reEmbed(g);
        invalidateCache(g);
        writeAudit("edit_grain", grainId, body.keySet().toString());

        return g;
    }

    /** 新增颗粒 + 向量化 */
    @Transactional(rollbackFor = Exception.class)
    public ExperienceGrain createGrain(Map<String, Object> body) {
        UUID skillId = UUID.fromString((String) body.get(KEY_SKILL_ID));
        var skill = skillRepository.findById(skillId)
            .orElseThrow(() -> new BusinessException(404,"分身不存在: " + skillId));

        ExperienceGrain g = ExperienceGrain.builder()
            .id(UUID.randomUUID()).spaceId(skill.getSpaceId())
            .sceneTag((String) body.get(KEY_SCENE_TAG))
            .sceneDescription((String) body.get(KEY_SCENE_DESCRIPTION))
            .expertThought((String) body.get(KEY_EXPERT_THOUGHT))
            .standardScript((String) body.get(KEY_STANDARD_SCRIPT))
            .commonMistakes((String) body.get(KEY_COMMON_MISTAKES))
            .applicableCondition((String) body.get(KEY_APPLICABLE_CONDITION))
            .weight(1.0).status("active").sourceType("manual")
            .helpfulCount(0).unhelpfulCount(0).createdAt(LocalDateTime.now())
            .build();
        grainRepository.save(g);
        reEmbed(g);
        writeAudit("create_grain", g.getId(), "{\"action\":\"create\"}");

        return g;
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

    /** 重新向量化 */
    private void reEmbed(ExperienceGrain g) {
        try {
            String text = (g.getExpertThought() != null ? g.getExpertThought() : "")
                + " " + (g.getStandardScript() != null ? g.getStandardScript() : "");
            if (text.trim().isEmpty()) { return; }
            float[] vec = embeddingService.embed(text);
            grainRepository.updateEmbedding(g.getId(), arrayToPgVector(vec));
            log.info("颗粒向量化完成 grainId={}", g.getId());
        } catch (Exception e) {
            log.error("向量化失败 grainId={}: {}", g.getId(), e.getMessage());
        }
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

    private String arrayToPgVector(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) { if (i > 0) { sb.append(","); } sb.append(vec[i]); }
        return sb.append("]").toString();
    }
}
