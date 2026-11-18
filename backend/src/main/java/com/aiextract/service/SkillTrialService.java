package com.aiextract.service;

import com.aiextract.common.PageResponse;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.Permission;
import com.aiextract.config.RolePermissions;
import com.aiextract.dto.SkillChatRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ChatChunk;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.GrainRetrieveLog;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillTrialMessage;
import com.aiextract.model.SkillTrialSession;
import com.aiextract.model.User;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.GrainRetrieveLogRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SkillTrialMessageRepository;
import com.aiextract.repository.SkillTrialSessionRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 试问台 —— 属主/管理员在发布前后演练分身，与真实员工数据零交叉。
 *
 * <p>关键约束（docs/76 第 8 节）：试问跑完整 RAG + 模型，但通过
 * {@code persistMode=TRIAL} 关闭 {@link ChatStreamService} 里的六个写库点，
 * 结果只写 skill_trial_session / skill_trial_message。</p>
 *
 * <p>鉴权在返回 Flux 之前完成（HTTP 线程），RAG/模型在订阅后跑在 boundedElastic。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillTrialService {

    /** 组装多轮上下文的最大消息数（一问一答算两条） */
    private static final int HISTORY_MESSAGES = 12;

    @Autowired
    @Lazy
    private SkillTrialService self;

    private final SkillRepository skillRepository;
    private final SpaceRepository spaceRepository;
    private final UserRepository userRepository;
    private final CompanyScopeService companyScopeService;
    private final ChatStreamService chatStreamService;
    private final SkillTrialSessionRepository sessionRepository;
    private final SkillTrialMessageRepository messageRepository;
    private final ExperienceGrainRepository grainRepository;
    private final GrainRetrieveLogRepository retrieveLogRepository;
    private final com.aiextract.repository.ReportRepository reportRepository;
    private final com.aiextract.repository.SkillMaterialRepository skillMaterialRepository;
    private final ObjectMapper objectMapper;

    // ============================================================
    // 权限
    // ============================================================

    /**
     * 试问/纠偏统一入口校验：企业隔离 → 组织分身 → 属主或管理员。
     *
     * <p>不能用 {@code requireSkillReadable}：已发布分身对全平台可见，跨企业也能读，
     * 而试问必须限定在本企业 + 属主。</p>
     */
    public Skill requireTrialAccess(UUID skillId, UUID userId) {
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        if (!companyScopeService.isUnrestrictedDataScope()
                && !companyScopeService.canAccessSkill(skillId)) {
            // 跨企业统一 404，避免暴露分身是否存在
            throw new BusinessException(404, "分身不存在");
        }
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        if ("organization".equals(skill.getType())) {
            throw new BusinessException(400, "组织分身暂不支持试问");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(401, "用户不存在"));
        boolean isAdmin = RolePermissions.hasPermission(user.getRole(), Permission.SKILL_MANAGE);
        boolean isOwner = isSpaceOwner(skill, userId);
        if (!isAdmin && !isOwner) {
            throw new BusinessException(403, "无权试问该分身");
        }
        return skill;
    }

    /** 已发布分身的属主不能直接改库，只能提交修改建议 */
    public boolean canApplyDirectly(Skill skill, UUID userId) {
        if (!"published".equals(skill.getStatus())) {
            return true;
        }
        User user = userRepository.findById(userId).orElse(null);
        return user != null && RolePermissions.hasPermission(user.getRole(), Permission.SKILL_MANAGE);
    }

    private boolean isSpaceOwner(Skill skill, UUID userId) {
        if (skill.getSpaceId() == null) {
            return false;
        }
        return spaceRepository.findById(skill.getSpaceId())
                .map(s -> userId.equals(s.getUserId()))
                .orElse(false);
    }

    // ============================================================
    // 试问
    // ============================================================

    /**
     * 试问一轮：鉴权在 HTTP 线程，RAG/模型在订阅后异步执行。
     */
    public Flux<ChatChunk> trialChat(UUID skillId, SkillChatRequest request, UUID userId) {
        Skill skill = requireTrialAccess(skillId, userId);
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return Flux.just(ChatChunk.error("消息不能为空"));
        }
        final UUID sessionId;
        String rawSessionId = request.getSessionId();
        if (rawSessionId != null && !rawSessionId.isBlank()) {
            UUID parsed;
            try {
                parsed = UUID.fromString(rawSessionId.trim());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "sessionId 格式不正确");
            }
            SkillTrialSession session = sessionRepository.findByIdAndSkillId(parsed, skillId)
                    .orElseThrow(() -> new BusinessException(404, "试问会话不存在"));
            if (!userId.equals(session.getUserId())) {
                throw new BusinessException(403, "无权访问该试问会话");
            }
            sessionId = parsed;
        } else {
            sessionId = UUID.randomUUID();
        }

        final boolean canApplyDirectly = canApplyDirectly(skill, userId);
        return Flux.defer(() -> runTrial(skill, sessionId, request, userId, canApplyDirectly))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ChatChunk> runTrial(Skill skill, UUID sessionId, SkillChatRequest request,
                                     UUID userId, boolean canApplyDirectly) {
        List<SkillTrialMessage> recent = new ArrayList<>(
                messageRepository.findBySessionIdOrderByCreatedAtDesc(
                        sessionId, PageRequest.of(0, HISTORY_MESSAGES)));
        Collections.reverse(recent);

        List<Map<String, String>> history = recent.stream()
                .map(m -> Map.of("role", m.getRole(), "content",
                        m.getContent() != null ? m.getContent() : ""))
                .toList();
        String ragHistory = formatRagHistory(recent);

        request.setPersistMode("TRIAL");
        request.setConversationId(null);
        request.setHistory(ragHistory);
        request.setTrialHistory(history.isEmpty() ? null : history);
        request.setIsTest(false);

        final StringBuilder aiContent = new StringBuilder();
        final AtomicBoolean hasError = new AtomicBoolean(false);
        final AtomicReference<String> grainIds = new AtomicReference<>("");
        final AtomicReference<String> grainTags = new AtomicReference<>("");
        final AtomicReference<String> avgSimilarity = new AtomicReference<>("");
        final AtomicReference<String> sourceNames = new AtomicReference<>("");

        Flux<ChatChunk> inner;
        try {
            inner = chatStreamService.chat(skill.getId(), request, userId, null)
                    .doOnNext(chunk -> {
                        String type = chunk.getType();
                        if ("content".equals(type) && chunk.getContent() != null) {
                            aiContent.append(chunk.getContent());
                        } else if ("source".equals(type) && chunk.getSourceType() == null) {
                            grainIds.set(chunk.getGrainIds() != null ? chunk.getGrainIds() : "");
                            grainTags.set(chunk.getGrainTags() != null ? chunk.getGrainTags() : "");
                            avgSimilarity.set(chunk.getAvgSimilarity() != null ? chunk.getAvgSimilarity() : "");
                            sourceNames.set(chunk.getSourceNames() != null ? chunk.getSourceNames() : "");
                        } else if ("error".equals(type)) {
                            hasError.set(true);
                        }
                    });
        } catch (Exception e) {
            log.warn("试问初始化失败 skillId={}: {}", skill.getId(), e.getMessage());
            return Flux.just(ChatChunk.error(e.getMessage() != null ? e.getMessage() : "试问失败"));
        }

        return Flux.concat(inner, Flux.defer(() -> {
            // 出错或空回复不落库，也不发试问事件 —— 避免留下半条记录
            if (hasError.get() || aiContent.length() == 0) {
                return Flux.empty();
            }
            List<ExperienceGrain> grains = loadGrains(grainIds.get());
            String metaJson = buildMetaJson(grains, avgSimilarity.get(), sourceNames.get());
            UUID assistantMessageId = UUID.randomUUID();
            boolean persisted = self.persistRound(sessionId, skill.getId(), userId,
                    request.getMessage(), request.getClientMessageId(), aiContent.toString(),
                    metaJson, assistantMessageId);
            if (!persisted) {
                log.info("试问幂等命中，跳过落库 sessionId={} clientMessageId={}",
                        sessionId, request.getClientMessageId());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId.toString());
            payload.put("messageId", assistantMessageId.toString());
            payload.put("avgSimilarity", avgSimilarity.get());
            payload.put("sourceNames", sourceNames.get());
            payload.put("canApplyDirectly", canApplyDirectly);
            payload.put("published", "published".equals(skill.getStatus()));
            payload.put("grains", grains.stream().map(this::toGrainMap).toList());
            return Flux.just(ChatChunk.event("trial", payload));
        }));
    }

    /**
     * 一轮试问落库：会话 upsert + 用户消息 + 助手消息，单事务。
     *
     * @return false 表示 clientMessageId 重复，本轮未写入
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean persistRound(UUID sessionId, UUID skillId, UUID userId, String question,
                                String clientMessageId, String answer, String metaJson,
                                UUID assistantMessageId) {
        String clientKey = clientMessageId != null && !clientMessageId.isBlank()
                ? clientMessageId.trim() : null;
        if (clientKey != null && messageRepository
                .findFirstBySessionIdAndClientMessageIdAndRole(
                        sessionId, clientKey, SkillTrialMessage.ROLE_USER).isPresent()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        SkillTrialSession session = sessionRepository.findById(sessionId)
                .filter(s -> skillId.equals(s.getSkillId()))
                .orElseGet(() -> SkillTrialSession.builder()
                        .id(sessionId).skillId(skillId).userId(userId)
                        .firstQuestion(truncate(question, 200))
                        .messageCount(0).createdAt(now).updatedAt(now)
                        .build());
        session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 2);
        session.setUpdatedAt(now);
        sessionRepository.save(session);

        messageRepository.save(SkillTrialMessage.builder()
                .id(UUID.randomUUID()).sessionId(sessionId).skillId(skillId)
                .role(SkillTrialMessage.ROLE_USER).content(question)
                .clientMessageId(clientKey).createdAt(now)
                .build());
        messageRepository.save(SkillTrialMessage.builder()
                .id(assistantMessageId).sessionId(sessionId).skillId(skillId)
                .role(SkillTrialMessage.ROLE_ASSISTANT).content(answer)
                .meta(metaJson).createdAt(now.plusNanos(1))
                .build());
        return true;
    }

    // ============================================================
    // 会话读取
    // ============================================================

    /** 员工只看自己的；管理员看本企业该分身全部。 */
    @Transactional(readOnly = true)
    public Map<String, Object> listSessions(UUID skillId, int page, int size, UUID userId) {
        Skill skill = requireTrialAccess(skillId, userId);
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 50);
        PageRequest pageable = PageRequest.of(p - 1, s);
        User user = userRepository.findById(userId).orElse(null);
        boolean isAdmin = user != null
                && RolePermissions.hasPermission(user.getRole(), Permission.SKILL_MANAGE);
        Page<SkillTrialSession> sessions = isAdmin
                ? sessionRepository.findBySkillIdOrderByUpdatedAtDesc(skill.getId(), pageable)
                : sessionRepository.findBySkillIdAndUserIdOrderByUpdatedAtDesc(skill.getId(), userId, pageable);
        return PageResponse.of(sessions, p, s);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getSession(UUID skillId, UUID sessionId, UUID userId) {
        Skill skill = requireTrialAccess(skillId, userId);
        SkillTrialSession session = sessionRepository.findByIdAndSkillId(sessionId, skill.getId())
                .orElseThrow(() -> new BusinessException(404, "试问会话不存在"));
        User user = userRepository.findById(userId).orElse(null);
        boolean isAdmin = user != null
                && RolePermissions.hasPermission(user.getRole(), Permission.SKILL_MANAGE);
        if (!isAdmin && !userId.equals(session.getUserId())) {
            throw new BusinessException(403, "无权访问该试问会话");
        }
        List<Map<String, Object>> messages = messageRepository
                .findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(m -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", m.getId().toString());
                    item.put("role", m.getRole());
                    item.put("content", m.getContent());
                    item.put("meta", parseJson(m.getMeta()));
                    item.put("createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : null);
                    return item;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", session.getId().toString());
        result.put("skillId", session.getSkillId().toString());
        result.put("firstQuestion", session.getFirstQuestion());
        result.put("messageCount", session.getMessageCount());
        result.put("createdAt", session.getCreatedAt() != null ? session.getCreatedAt().toString() : null);
        result.put("messages", messages);
        return result;
    }

    /** 试问右栏 / 纠偏面板读取颗粒详情：属主可读自己空间下的颗粒。 */
    @Transactional(readOnly = true)
    public Map<String, Object> getGrain(UUID skillId, UUID grainId, UUID userId) {
        Skill skill = requireTrialAccess(skillId, userId);
        ExperienceGrain grain = grainRepository.findById(grainId)
                .orElseThrow(() -> new BusinessException(404, "颗粒不存在"));
        if (skill.getSpaceId() == null || !skill.getSpaceId().equals(grain.getSpaceId())) {
            throw new BusinessException(404, "颗粒不属于该分身");
        }
        return toGrainMap(grain);
    }

    /**
     * 属主可读的经验溯源 —— 与 /admin/grains/traceability 同形状，但只允许
     * 本分身空间下的颗粒，且权限门槛是 SKILL_USE + 属主/管理员。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> traceability(UUID skillId, String grainIdsCsv, UUID userId) {
        Skill skill = requireTrialAccess(skillId, userId);
        List<UUID> ids = new ArrayList<>();
        if (grainIdsCsv != null && !grainIdsCsv.isBlank()) {
            for (String part : grainIdsCsv.split(",")) {
                String s = part.trim();
                if (s.isEmpty()) continue;
                try {
                    ids.add(UUID.fromString(s));
                } catch (IllegalArgumentException e) {
                    throw new BusinessException(400, "grainIds 格式不正确");
                }
            }
        }
        if (ids.size() > 50) {
            throw new BusinessException(400, "一次最多查询 50 个颗粒");
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, ExperienceGrain> grainMap = grainRepository.findAllById(ids).stream()
                .collect(java.util.stream.Collectors.toMap(ExperienceGrain::getId, g -> g));
        List<UUID> reportIds = new ArrayList<>();
        List<UUID> materialIds = new ArrayList<>();
        for (UUID id : ids) {
            ExperienceGrain g = grainMap.get(id);
            if (g == null) continue;
            if (skill.getSpaceId() == null || !skill.getSpaceId().equals(g.getSpaceId())) {
                throw new BusinessException(403, "无权访问该颗粒");
            }
            if (g.getReportId() != null) reportIds.add(g.getReportId());
            if (g.getSourceMaterialId() != null) materialIds.add(g.getSourceMaterialId());
        }
        Map<UUID, com.aiextract.model.Report> reportMap = new LinkedHashMap<>();
        reportRepository.findAllById(reportIds).forEach(r -> reportMap.put(r.getId(), r));
        Map<UUID, com.aiextract.model.SkillMaterial> materialMap = new LinkedHashMap<>();
        skillMaterialRepository.findAllById(materialIds).forEach(m -> materialMap.put(m.getId(), m));

        List<Map<String, Object>> result = new ArrayList<>();
        for (UUID id : ids) {
            ExperienceGrain grain = grainMap.get(id);
            if (grain == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("grainId", id.toString());
            item.put("spaceId", grain.getSpaceId() != null ? grain.getSpaceId().toString() : null);
            item.put("sceneDescription", grain.getSceneDescription());
            item.put("expertThought", grain.getExpertThought());
            item.put("standardScript", grain.getStandardScript());
            item.put("commonMistakes", grain.getCommonMistakes());
            item.put("qualityScore", grain.getQualityScore());
            item.put("difficultyLevel", grain.getDifficultyLevel());
            if (grain.getReportId() != null) {
                var report = reportMap.get(grain.getReportId());
                if (report != null) {
                    item.put("reportTitle", report.getTitle());
                    item.put("reportId", report.getId().toString());
                }
            }
            if (grain.getSourceMaterialId() != null) {
                var material = materialMap.get(grain.getSourceMaterialId());
                if (material != null) {
                    item.put("sourceName", material.getFileName());
                    item.put("sourceType", "file_upload");
                    String content = material.getParsedContent();
                    item.put("sourceSnippet", content != null && content.length() > 500
                            ? content.substring(0, 500) + "…" : content);
                }
            }
            result.add(item);
        }
        return result;
    }

    /** 溯源：属主查看某颗粒近期被检索的记录（替代 /admin/grains/traceability 的 GRAIN_MANAGE 门槛）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> traceGrain(UUID skillId, UUID grainId, UUID userId) {
        Skill skill = requireTrialAccess(skillId, userId);
        ExperienceGrain grain = grainRepository.findById(grainId)
                .orElseThrow(() -> new BusinessException(404, "颗粒不存在"));
        if (skill.getSpaceId() == null || !skill.getSpaceId().equals(grain.getSpaceId())) {
            throw new BusinessException(404, "颗粒不属于该分身");
        }
        List<GrainRetrieveLog> logs = retrieveLogRepository.findByGrainIdOrderByCreatedAtDesc(
                grainId, PageRequest.of(0, 20));
        return logs.stream().map(l -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", l.getId().toString());
            item.put("query", l.getOriginalQuery());
            item.put("sceneTag", l.getSceneTag());
            item.put("similarity", l.getSimilarity());
            item.put("tier", l.getTier());
            item.put("createdAt", l.getCreatedAt() != null ? l.getCreatedAt().toString() : null);
            return item;
        }).toList();
    }

    // ============================================================
    // helpers
    // ============================================================

    private List<ExperienceGrain> loadGrains(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (String part : csv.split(",")) {
            try {
                ids.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException ignored) {
                // 非 UUID 片段直接跳过
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, ExperienceGrain> byId = new LinkedHashMap<>();
        grainRepository.findAllById(ids).forEach(g -> byId.put(g.getId(), g));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private Map<String, Object> toGrainMap(ExperienceGrain g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId().toString());
        m.put("sceneTag", g.getSceneTag());
        m.put("sceneDescription", g.getSceneDescription());
        m.put("expertThought", g.getExpertThought());
        m.put("standardScript", g.getStandardScript());
        m.put("commonMistakes", g.getCommonMistakes());
        m.put("applicableCondition", g.getApplicableCondition());
        m.put("qualityScore", g.getQualityScore());
        return m;
    }

    private String buildMetaJson(List<ExperienceGrain> grains, String avgSimilarity, String sourceNames) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("grainIds", grains.stream().map(g -> g.getId().toString()).toList());
        meta.put("grainTags", grains.stream().map(ExperienceGrain::getSceneTag)
                .filter(java.util.Objects::nonNull).distinct().toList());
        meta.put("avgSimilarity", avgSimilarity);
        meta.put("sourceNames", sourceNames);
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            log.warn("试问 meta 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return raw;
        }
    }

    private String formatRagHistory(List<SkillTrialMessage> recent) {
        if (recent.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (SkillTrialMessage m : recent) {
            sb.append("user".equals(m.getRole()) ? "用户" : "分身").append("：");
            String c = m.getContent();
            if (c != null) {
                sb.append(c.length() > 200 ? c.substring(0, 200) : c);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
