package com.aiextract.service;

import com.aiextract.config.Permission;
import com.aiextract.config.PromptLoader;
import com.aiextract.config.PromptSecurity;
import com.aiextract.config.RolePermissions;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AdminAuditLog;
import com.aiextract.model.ChatChunk;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.GrainEditHistory;
import com.aiextract.model.GrainRefineDraft;
import com.aiextract.model.Skill;
import com.aiextract.model.User;
import com.aiextract.repository.AdminAuditLogRepository;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.GrainEditHistoryRepository;
import com.aiextract.repository.GrainRefineDraftRepository;
import com.aiextract.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 对话式纠偏 —— 属主说人话，AI 落成五段修改，确认后写回颗粒并重建向量。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>AI 调用不在事务里；落草稿是 AI 成功之后的单事务，失败不留半成品</li>
 *   <li>apply 前比对 {@code base_*}：颗粒被别人改过就 409，不静默覆盖</li>
 *   <li>已发布分身的属主不能直接改库，只能提交修改建议（管理员可直接改，需二次确认）</li>
 * </ol>
 *
 * @author AI Extract Team
 * @since 2026-11-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrainRefineService {

    /** 字段 → 颗粒 setter 的稳定顺序，保证 diff 与历史记录顺序一致 */
    private enum Field {
        SCENE("sceneDescription"),
        THOUGHT("expertThought"),
        SCRIPT("standardScript"),
        MISTAKE("commonMistakes"),
        CONDITION("applicableCondition");

        final String key;

        Field(String key) {
            this.key = key;
        }
    }

    @Autowired
    @Lazy
    private GrainRefineService self;

    private final SkillTrialService trialService;
    private final GrainCorrectionService correctionService;
    private final GrainEmbeddingRefresher embeddingRefresher;
    private final ExperienceGrainRepository grainRepository;
    private final GrainRefineDraftRepository draftRepository;
    private final GrainEditHistoryRepository editHistoryRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final ChatStreamAdapter chatStreamAdapter;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    // ============================================================
    // 对话式纠偏（SSE）
    // ============================================================

    /**
     * 纠偏一轮：鉴权在 HTTP 线程，模型调用在订阅后跑在 boundedElastic。
     */
    public Flux<ChatChunk> refine(UUID skillId, UUID grainId, String draftId, String message, UUID userId) {
        Skill skill = trialService.requireTrialAccess(skillId, userId);
        ExperienceGrain grain = requireGrain(skill, grainId);
        String msg = message == null ? "" : message.trim();
        if (msg.isEmpty()) {
            return Flux.just(ChatChunk.error("请说明哪里不对"));
        }
        final GrainRefineDraft draft = draftId == null || draftId.isBlank()
                ? null
                : requireDraft(parseDraftId(draftId), skillId, grainId, userId);

        return Flux.defer(() -> runRefine(skill, grain, draft, msg, userId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ChatChunk> runRefine(Skill skill, ExperienceGrain grain,
                                      GrainRefineDraft draft, String message, UUID userId) {
        // 改前的基线：续接草稿用原 base，新草稿用当前颗粒
        Map<String, String> base = draft != null ? baseOf(draft) : baseOf(grain);

        // PromptLoader 按文件名加载，必须带 .md 后缀（不带会拿到空模板）
        String prompt = promptLoader.format("grain_refine.md", Map.of(
                "grain_json", PromptSecurity.wrapUntrusted(toJson(base, grain.getSceneTag())),
                "conversation", draft != null ? formatTranscript(draft.getTranscript()) : "（刚开始）",
                "user_message", message));

        RefineResult result;
        try {
            result = callModel(prompt);
        } catch (Exception e) {
            log.warn("纠偏模型调用失败 grainId={}: {}", grain.getId(), e.getMessage());
            return Flux.just(ChatChunk.error("AI 修订失败，请重试"));
        }

        List<String> changed = computeChanged(base, result.fields());
        String reply = PiiMasker.mask(result.reply());
        Map<String, String> masked = maskFields(result.fields());

        UUID savedDraftId;
        try {
            savedDraftId = self.saveDraft(skill.getId(), grain.getId(), userId, draft,
                    base, masked, changed, message, reply, result.confidence());
        } catch (Exception e) {
            log.error("纠偏草稿落库失败 grainId={}", grain.getId(), e);
            return Flux.just(ChatChunk.error("修订保存失败，请重试"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("draftId", savedDraftId.toString());
        data.put("fields", masked);
        data.put("changed", changed);
        data.put("confidence", result.confidence());
        data.put("reply", reply);
        data.put("published", "published".equals(skill.getStatus()));
        return Flux.just(
                ChatChunk.content(reply != null ? reply : "已按你说的改好，确认后生效。"),
                ChatChunk.event("refine", data));
    }

    /**
     * 落草稿：AI 成功之后才写，单事务。
     *
     * @return 草稿 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public UUID saveDraft(UUID skillId, UUID grainId, UUID userId, GrainRefineDraft existing,
                          Map<String, String> base, Map<String, String> fields, List<String> changed,
                          String userMessage, String reply, String confidence) {
        LocalDateTime now = LocalDateTime.now();
        GrainRefineDraft d = existing;
        if (d == null) {
            d = GrainRefineDraft.builder()
                    .id(UUID.randomUUID()).skillId(skillId).grainId(grainId).userId(userId)
                    .status(GrainRefineDraft.STATUS_DRAFT)
                    .baseScene(base.get(Field.SCENE.key)).baseThought(base.get(Field.THOUGHT.key))
                    .baseScript(base.get(Field.SCRIPT.key)).baseMistake(base.get(Field.MISTAKE.key))
                    .baseCondition(base.get(Field.CONDITION.key))
                    .embeddingStatus(GrainRefineDraft.EMBEDDING_PENDING)
                    .createdAt(now)
                    .build();
        }
        // 模型漏字段时回落到 base，绝不能用 null 覆盖已有内容
        d.setDraftScene(pick(fields, base, Field.SCENE));
        d.setDraftThought(pick(fields, base, Field.THOUGHT));
        d.setDraftScript(pick(fields, base, Field.SCRIPT));
        d.setDraftMistake(pick(fields, base, Field.MISTAKE));
        d.setDraftCondition(pick(fields, base, Field.CONDITION));
        d.setChangedFields(toJsonArray(changed));
        d.setTranscript(appendTranscript(d.getTranscript(), userMessage, reply, confidence));
        draftRepository.save(d);
        return d.getId();
    }

    // ============================================================
    // 应用 / 回滚 / 丢弃 / 查询
    // ============================================================

    /**
     * 应用草稿。
     *
     * @param confirm 已发布分身的直接修改需要显式确认「立即对所有人生效」
     */
    public Map<String, Object> apply(UUID skillId, UUID grainId, UUID draftId,
                                     boolean confirm, Map<String, String> overrides, UUID userId) {
        Skill skill = trialService.requireTrialAccess(skillId, userId);
        GrainRefineDraft draft = requireDraft(draftId, skillId, grainId, userId);
        boolean published = "published".equals(skill.getStatus());
        boolean isAdmin = isAdmin(userId);
        if (published && !isAdmin) {
            throw new BusinessException(403, "分身已发布，请提交修改建议，由管理员审核后生效");
        }
        if (published && !confirm) {
            throw new BusinessException(400, "该修改会立即对所有使用者生效，请确认后再应用");
        }
        GrainRefineDraft applied = self.applyTx(draft.getId(), overrides, userId);
        embeddingRefresher.refreshAsync(applied.getGrainId(), applied.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("draftId", applied.getId().toString());
        result.put("changed", parseJsonArray(applied.getChangedFields()));
        result.put("embeddingStatus", GrainRefineDraft.EMBEDDING_PENDING);
        return result;
    }

    /** 短事务：只做 DB 写入；向量重建由调用方在事务提交后异步触发 */
    @Transactional(rollbackFor = Exception.class)
    public GrainRefineDraft applyTx(UUID draftId, Map<String, String> overrides, UUID userId) {
        GrainRefineDraft d = draftRepository.findById(draftId)
                .orElseThrow(() -> new BusinessException(404, "修订草稿不存在"));
        if (!GrainRefineDraft.STATUS_DRAFT.equals(d.getStatus())) {
            throw new BusinessException(409, "该修订已处理，请重新纠偏");
        }
        ExperienceGrain grain = grainRepository.findById(d.getGrainId())
                .orElseThrow(() -> new BusinessException(404, "颗粒已不存在"));
        assertBaseUnchanged(grain, d);

        // 默认整条接受；前端勾选字段时只应用勾选项（可附带用户手工改过的文案）
        Map<String, String> target = new LinkedHashMap<>(baseOf(d));
        if (overrides == null || overrides.isEmpty()) {
            target.putAll(fieldsOf(d));
        } else {
            for (Field f : Field.values()) {
                if (overrides.containsKey(f.key)) {
                    target.put(f.key, overrides.get(f.key));
                }
            }
        }
        List<String> changed = computeChanged(baseOf(d), target);
        if (changed.isEmpty()) {
            throw new BusinessException(400, "没有任何字段被修改");
        }
        grain.setSceneDescription(target.get(Field.SCENE.key));
        grain.setExpertThought(target.get(Field.THOUGHT.key));
        grain.setStandardScript(target.get(Field.SCRIPT.key));
        grain.setCommonMistakes(target.get(Field.MISTAKE.key));
        grain.setApplicableCondition(target.get(Field.CONDITION.key));
        grainRepository.save(grain);

        // 草稿改成实际应用的版本，回滚时按 applied 内容比对
        d.setDraftScene(target.get(Field.SCENE.key));
        d.setDraftThought(target.get(Field.THOUGHT.key));
        d.setDraftScript(target.get(Field.SCRIPT.key));
        d.setDraftMistake(target.get(Field.MISTAKE.key));
        d.setDraftCondition(target.get(Field.CONDITION.key));
        d.setChangedFields(toJsonArray(changed));

        writeEditHistory(grain.getId(), baseOf(d), target, changed, userId, "对话式纠偏");
        writeAudit("refine_grain", grain.getId(),
                Map.of("draftId", d.getId().toString(), "changed", changed));

        d.setStatus(GrainRefineDraft.STATUS_APPLIED);
        d.setAppliedAt(LocalDateTime.now());
        d.setEmbeddingStatus(GrainRefineDraft.EMBEDDING_PENDING);
        return draftRepository.save(d);
    }

    /** 回滚：只允许最近一次 applied，且颗粒当前内容必须仍是那次修改的结果 */
    public Map<String, Object> revert(UUID skillId, UUID grainId, UUID draftId, UUID userId) {
        Skill skill = trialService.requireTrialAccess(skillId, userId);
        boolean published = "published".equals(skill.getStatus());
        if (published && !isAdmin(userId)) {
            throw new BusinessException(403, "分身已发布，回滚请联系管理员");
        }
        GrainRefineDraft draft = requireDraft(draftId, skillId, grainId, userId);
        GrainRefineDraft reverted = self.revertTx(draft.getId(), userId);
        embeddingRefresher.refreshAsync(reverted.getGrainId(), reverted.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("draftId", reverted.getId().toString());
        result.put("status", reverted.getStatus());
        result.put("embeddingStatus", GrainRefineDraft.EMBEDDING_PENDING);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public GrainRefineDraft revertTx(UUID draftId, UUID userId) {
        GrainRefineDraft d = draftRepository.findById(draftId)
                .orElseThrow(() -> new BusinessException(404, "修订草稿不存在"));
        if (!GrainRefineDraft.STATUS_APPLIED.equals(d.getStatus())) {
            throw new BusinessException(409, "只有已应用的修订可以回滚");
        }
        GrainRefineDraft latest = draftRepository
                .findFirstByGrainIdAndStatusOrderByAppliedAtDesc(
                        d.getGrainId(), GrainRefineDraft.STATUS_APPLIED)
                .orElseThrow(() -> new BusinessException(409, "没有可回滚的修订"));
        if (!latest.getId().equals(d.getId())) {
            throw new BusinessException(409, "只能回滚最近一次修改");
        }
        ExperienceGrain grain = grainRepository.findById(d.getGrainId())
                .orElseThrow(() -> new BusinessException(404, "颗粒已不存在"));
        if (!sameFields(baseOf(grain), fieldsOf(d))) {
            throw new BusinessException(409, "颗粒已被再次修改，无法回滚到这条修订");
        }

        grain.setSceneDescription(d.getBaseScene());
        grain.setExpertThought(d.getBaseThought());
        grain.setStandardScript(d.getBaseScript());
        grain.setCommonMistakes(d.getBaseMistake());
        grain.setApplicableCondition(d.getBaseCondition());
        grainRepository.save(grain);

        writeEditHistory(grain.getId(), fieldsOf(d), baseOf(d),
                computeChanged(fieldsOf(d), baseOf(d)), userId, "回滚对话式纠偏");
        writeAudit("revert_refine_grain", grain.getId(),
                Map.of("draftId", d.getId().toString()));

        d.setStatus(GrainRefineDraft.STATUS_REVERTED);
        d.setEmbeddingStatus(GrainRefineDraft.EMBEDDING_PENDING);
        return draftRepository.save(d);
    }

    @Transactional(rollbackFor = Exception.class)
    public void discard(UUID skillId, UUID grainId, UUID draftId, UUID userId) {
        trialService.requireTrialAccess(skillId, userId);
        GrainRefineDraft d = requireDraft(draftId, skillId, grainId, userId);
        if (!GrainRefineDraft.STATUS_DRAFT.equals(d.getStatus())) {
            throw new BusinessException(409, "该修订已处理");
        }
        d.setStatus(GrainRefineDraft.STATUS_DISCARDED);
        draftRepository.save(d);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getDraft(UUID skillId, UUID grainId, UUID draftId, UUID userId) {
        trialService.requireTrialAccess(skillId, userId);
        GrainRefineDraft d = requireDraft(draftId, skillId, grainId, userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("draftId", d.getId().toString());
        m.put("status", d.getStatus());
        m.put("embeddingStatus", d.getEmbeddingStatus());
        m.put("changed", parseJsonArray(d.getChangedFields()));
        m.put("base", baseOf(d));
        m.put("draft", fieldsOf(d));
        m.put("appliedAt", d.getAppliedAt() != null ? d.getAppliedAt().toString() : null);
        return m;
    }

    /** 已发布分身的属主：把草稿转成纠错建议，走现有审核队列。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitSuggestion(UUID skillId, UUID grainId, UUID draftId,
                                               String reason, UUID userId) {
        Skill skill = trialService.requireTrialAccess(skillId, userId);
        if (!"published".equals(skill.getStatus())) {
            throw new BusinessException(400, "未发布分身可以直接应用修订");
        }
        if (isAdmin(userId)) {
            throw new BusinessException(400, "管理员可直接应用，无需提交建议");
        }
        GrainRefineDraft d = requireDraft(draftId, skillId, grainId, userId);
        if (!GrainRefineDraft.STATUS_DRAFT.equals(d.getStatus())) {
            throw new BusinessException(409, "该修订已处理");
        }
        String note = reason == null || reason.isBlank()
                ? "通过对话式纠偏提交的修改建议" : reason.trim();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("reason", note);
        body.put("sceneDescription", d.getDraftScene());
        body.put("expertThought", d.getDraftThought());
        body.put("standardScript", d.getDraftScript());
        body.put("commonMistakes", d.getDraftMistake());
        var suggestion = correctionService.submit(skillId, grainId, body);
        d.setStatus(GrainRefineDraft.STATUS_DISCARDED);
        draftRepository.save(d);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("suggestionId", suggestion.getId().toString());
        result.put("draftId", d.getId().toString());
        return result;
    }

    // ============================================================
    // 模型调用与解析
    // ============================================================

    private record RefineResult(String reply, Map<String, String> fields, String confidence) { }

    private RefineResult callModel(String prompt) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String raw = chatStreamAdapter.chat(prompt);
                return parseResult(raw);
            } catch (RuntimeException e) {
                last = e;
                log.warn("纠偏 JSON 解析失败（第 {} 次）: {}", attempt, e.getMessage());
            }
        }
        throw last != null ? last : new BusinessException(502, "AI 修订失败");
    }

    @SuppressWarnings("unchecked")
    private RefineResult parseResult(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(502, "AI 返回为空");
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new BusinessException(502, "AI 返回不是 JSON");
        }
        try {
            Map<String, Object> root = objectMapper.readValue(
                    raw.substring(start, end + 1), new TypeReference<Map<String, Object>>() { });
            String reply = root.get("reply") instanceof String r ? r : null;
            String confidence = root.get("confidence") instanceof String c ? c : "medium";
            Object fieldsNode = root.get("fields");
            if (!(fieldsNode instanceof Map<?, ?> fieldsRaw)) {
                throw new BusinessException(502, "AI 返回缺少 fields");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (Field f : Field.values()) {
                Object v = fieldsRaw.get(f.key);
                fields.put(f.key, v != null ? String.valueOf(v) : null);
            }
            return new RefineResult(reply, fields, confidence);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "AI 返回 JSON 无法解析");
        }
    }

    // ============================================================
    // helpers
    // ============================================================

    private ExperienceGrain requireGrain(Skill skill, UUID grainId) {
        ExperienceGrain grain = grainRepository.findById(grainId)
                .orElseThrow(() -> new BusinessException(404, "颗粒不存在"));
        if (skill.getSpaceId() == null || !skill.getSpaceId().equals(grain.getSpaceId())) {
            throw new BusinessException(404, "颗粒不属于该分身");
        }
        return grain;
    }

    private UUID parseDraftId(String draftId) {
        try {
            return UUID.fromString(draftId.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "draftId 格式不正确");
        }
    }

    /** 草稿必须属于该分身/颗粒；属主只能读写自己的草稿，管理员不限 */
    private GrainRefineDraft requireDraft(UUID id, UUID skillId, UUID grainId, UUID userId) {
        GrainRefineDraft d = draftRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "修订草稿不存在"));
        if (!skillId.equals(d.getSkillId()) || !grainId.equals(d.getGrainId())) {
            throw new BusinessException(404, "修订草稿不存在");
        }
        if (!userId.equals(d.getUserId()) && !isAdmin(userId)) {
            throw new BusinessException(403, "无权访问该修订草稿");
        }
        return d;
    }

    private boolean isAdmin(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        return user != null && RolePermissions.hasPermission(user.getRole(), Permission.SKILL_MANAGE);
    }

    /** 模型漏字段 → 回落到 base；模型给空串 → 允许（表示清空该段） */
    private String pick(Map<String, String> fields, Map<String, String> base, Field f) {
        String v = fields.get(f.key);
        return v != null ? v : base.get(f.key);
    }

    private Map<String, String> baseOf(ExperienceGrain g) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(Field.SCENE.key, g.getSceneDescription());
        m.put(Field.THOUGHT.key, g.getExpertThought());
        m.put(Field.SCRIPT.key, g.getStandardScript());
        m.put(Field.MISTAKE.key, g.getCommonMistakes());
        m.put(Field.CONDITION.key, g.getApplicableCondition());
        return m;
    }

    private Map<String, String> baseOf(GrainRefineDraft d) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(Field.SCENE.key, d.getBaseScene());
        m.put(Field.THOUGHT.key, d.getBaseThought());
        m.put(Field.SCRIPT.key, d.getBaseScript());
        m.put(Field.MISTAKE.key, d.getBaseMistake());
        m.put(Field.CONDITION.key, d.getBaseCondition());
        return m;
    }

    private Map<String, String> fieldsOf(GrainRefineDraft d) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(Field.SCENE.key, d.getDraftScene());
        m.put(Field.THOUGHT.key, d.getDraftThought());
        m.put(Field.SCRIPT.key, d.getDraftScript());
        m.put(Field.MISTAKE.key, d.getDraftMistake());
        m.put(Field.CONDITION.key, d.getDraftCondition());
        return m;
    }

    private Map<String, String> maskFields(Map<String, String> fields) {
        Map<String, String> masked = new LinkedHashMap<>();
        fields.forEach((k, v) -> masked.put(k, v != null ? PiiMasker.mask(v) : null));
        return masked;
    }

    /** 服务端按 base/draft 实际差异计算，不信任模型的 changed 字段 */
    private List<String> computeChanged(Map<String, String> base, Map<String, String> draft) {
        List<String> changed = new ArrayList<>();
        for (Field f : Field.values()) {
            if (!Objects.equals(normalize(base.get(f.key)), normalize(draft.get(f.key)))) {
                changed.add(f.key);
            }
        }
        return changed;
    }

    private boolean sameFields(Map<String, String> a, Map<String, String> b) {
        for (Field f : Field.values()) {
            if (!Objects.equals(normalize(a.get(f.key)), normalize(b.get(f.key)))) {
                return false;
            }
        }
        return true;
    }

    private void assertBaseUnchanged(ExperienceGrain grain, GrainRefineDraft d) {
        if (!sameFields(baseOf(grain), baseOf(d))) {
            throw new BusinessException(409, "颗粒已被他人修改，请重新试问后再纠偏");
        }
    }

    private String normalize(String v) {
        return v == null ? "" : v.trim();
    }

    private String toJson(Map<String, String> fields, String sceneTag) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("sceneTag", sceneTag);
        json.putAll(fields);
        try {
            return objectMapper.writeValueAsString(json);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String toJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> parseJsonArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private String appendTranscript(String existing, String userMessage, String reply, String confidence) {
        List<Map<String, Object>> transcript = new ArrayList<>();
        if (existing != null && !existing.isBlank()) {
            try {
                transcript = objectMapper.readValue(existing, new TypeReference<List<Map<String, Object>>>() { });
            } catch (Exception e) {
                transcript = new ArrayList<>();
            }
        }
        transcript.add(Map.of("role", "user", "content", userMessage));
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", reply != null ? reply : "");
        assistant.put("confidence", confidence);
        transcript.add(assistant);
        try {
            return objectMapper.writeValueAsString(transcript);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String formatTranscript(String raw) {
        if (raw == null || raw.isBlank()) {
            return "（刚开始）";
        }
        try {
            List<Map<String, Object>> transcript = objectMapper.readValue(
                    raw, new TypeReference<List<Map<String, Object>>>() { });
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> item : transcript) {
                Object role = item.get("role");
                Object content = item.get("content");
                sb.append("assistant".equals(role) ? "助手" : "销冠").append("：")
                        .append(content != null ? content : "").append("\n");
            }
            return PromptSecurity.wrapHistory(sb.toString());
        } catch (Exception e) {
            return "（刚开始）";
        }
    }

    private void writeEditHistory(UUID grainId, Map<String, String> before, Map<String, String> after,
                                  List<String> changed, UUID userId, String note) {
        String editedBy = userRepository.findById(userId).map(User::getName).orElse("属主");
        LocalDateTime now = LocalDateTime.now();
        for (String key : changed) {
            editHistoryRepository.save(GrainEditHistory.builder()
                    .id(UUID.randomUUID()).grainId(grainId)
                    .fieldName(key)
                    .oldValue(before.get(key))
                    .newValue(after.get(key))
                    .editedBy(editedBy)
                    .editNote(note)
                    .createdAt(now)
                    .build());
        }
    }

    private void writeAudit(String action, UUID targetId, Map<String, ?> detail) {
        try {
            UUID operator = TokenContext.get();
            auditLogRepository.save(AdminAuditLog.builder()
                    .id(UUID.randomUUID())
                    .adminId(operator != null ? operator
                            : UUID.fromString("00000000-0000-0000-0000-000000000000"))
                    .action(action)
                    .targetType("grain")
                    .targetId(targetId)
                    .detail(objectMapper.writeValueAsString(detail))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.debug("审计日志写入失败: {}", e.getMessage());
        }
    }
}
