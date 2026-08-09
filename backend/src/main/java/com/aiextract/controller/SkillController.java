package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.BaseController;
import com.aiextract.common.ErrorMessages;
import com.aiextract.config.SseAdapter;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.*;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillMaterial;
import com.aiextract.model.SkillShare;
import com.aiextract.model.Space;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.SkillMaterialRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.service.ChatStreamService;
import com.aiextract.service.ConversationService;
import com.aiextract.service.GrainRecommendationService;
import com.aiextract.service.OrganizationSkillService;
import com.aiextract.service.PracticeDemoService;
import com.aiextract.service.QueryGate;
import com.aiextract.service.SkillService;
import com.aiextract.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/skills")
/**
 * @author AI Extract Team
 */
@RequiredArgsConstructor
public class SkillController extends BaseController {

    private final SkillService skillService;
    private final ChatStreamService chatStreamService;
    private final ConversationService conversationService;
    private final GrainRecommendationService grainRecommendationService;
    private final ObjectMapper objectMapper;
    private final SkillRepository skillRepository;
    private final com.aiextract.repository.SpaceRepository spaceRepository;
    private final ExperienceGrainRepository grainRepository;
    private final SkillMaterialRepository skillMaterialRepository;
    private final PracticeDemoService practiceDemoService;
    private final QueryGate queryGate;
    private final OrganizationSkillService orgSkillService;

    /**
     * 从 HttpServletRequest 提取客户端 IP。
     * 优先取 X-Forwarded-For 头（代理/负载均衡后），fallback 到 remoteAddr。
     */
    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim(); // 第一个 IP 是原始客户端
        }
        return request.getRemoteAddr();
    }

    private final com.aiextract.service.ShareService shareService;
    private final com.aiextract.repository.AdminAuditLogRepository auditLogRepository;

    @PostMapping(value = "/{skillId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String skillId,
            @Valid @RequestBody SkillChatRequest request,
            HttpServletRequest httpRequest) {
        // QueryGate 前置拦截 — 四层门控，短路返回
        var blocked = queryGate.audit(request.getMessage(), extractUserId(), extractRole(), getClientIp(httpRequest));
        if (blocked != null)
            return SseAdapter.fromFlux(blocked);

        return SseAdapter.fromFlux(
                chatStreamService.chat(UUID.fromString(skillId), request, extractUserId(), extractRole()));
    }

    @GetMapping("/{skillId}/conversations")
    public ApiResponse<List<Map<String, Object>>> listConversations(
            @PathVariable String skillId) {
        UUID userId = extractUserId();
        return ApiResponse.success(conversationService.listConversations(skillId, userId));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<List<Map<String, Object>>> getConversationMessages(
            @PathVariable String conversationId) {
        return ApiResponse.success(conversationService.getConversationMessages(conversationId, extractUserId()));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> deleteConversation(@PathVariable String conversationId) {
        conversationService.deleteConversation(conversationId, extractUserId());
        return ApiResponse.success();
    }

    @PostMapping("/{skillId}/practice/start")
    public ApiResponse<PracticeStartResponse> startPractice(
            @PathVariable String skillId, @RequestBody PracticeStartRequest request) {
        return ApiResponse.success(skillService.startPractice(skillId, request, extractUserId()));
    }

    @PostMapping(value = "/{skillId}/practice/respond", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter respondPractice(
            @PathVariable String skillId,
            @Valid @RequestBody PracticeRespondRequest request,
            HttpServletRequest httpRequest) {
        // QueryGate 前置拦截 — 四层门控
        var blocked = queryGate.audit(request.getMessage(), extractUserId(), extractRole(), getClientIp(httpRequest));
        if (blocked != null)
            return SseAdapter.fromFlux(blocked);

        return SseAdapter.fromFlux(
                chatStreamService.respondPractice(UUID.fromString(skillId), request, extractUserId(), extractRole()));
    }

    /** 对练每轮评价 — 销冠答案对比 + 技法 + 溯源（非流式，直接返回 JSON） */
    @PostMapping("/{skillId}/practice/evaluate-round")
    public ApiResponse<Map<String, Object>> evaluatePracticeRound(
            @PathVariable String skillId,
            @RequestBody Map<String, Object> body) {
        String sceneTag = (String) body.getOrDefault("sceneTag", "");
        String customerMessage = (String) body.getOrDefault("customerMessage", "");
        String myResponse = (String) body.getOrDefault("myResponse", "");
        String previousChampionAnswer = (String) body.getOrDefault("previousChampionAnswer", "");
        int retryCount = body.containsKey("retryCount") ? ((Number) body.get("retryCount")).intValue() : 0;
        if (customerMessage.isBlank() || myResponse.isBlank()) {
            return ApiResponse.error(400, "客户消息和你的回答不能为空");
        }
        try {
            return ApiResponse.success(
                    practiceDemoService.evaluatePracticeResponse(
                            UUID.fromString(skillId), sceneTag, customerMessage, myResponse, previousChampionAnswer,
                            retryCount));
        } catch (Exception e) {
            log.error("evaluate-round error", e);
            return ApiResponse.error(500, "评估服务暂时不可用，请稍后重试");
        }
    }

    @PostMapping(value = "/enterprise/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter enterpriseChat(@Valid @RequestBody SkillChatRequest request) {
        UUID companyId = TokenContext.getCompanyId();
        return SseAdapter.fromFlux(chatStreamService.enterpriseChat(request, companyId));
    }

    @PostMapping("/{skillId}/feedback")
    public ApiResponse<Void> submitFeedback(
            @PathVariable String skillId, @RequestBody FeedbackRequest request) {
        skillService.submitFeedback(skillId, request);
        return ApiResponse.success();
    }

    /**
     * 获取推荐问题 —— 分身对话中 RAG 无匹配时展示给用户。
     * 从覆盖度最高的场景生成 3 个问题，前端展示为可点击按钮。
     */
    @GetMapping("/{skillId}/suggested")
    public ApiResponse<List<String>> getSuggestedQuestions(@PathVariable String skillId) {
        Skill skill = skillRepository.findById(UUID.fromString(skillId))
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));
        return ApiResponse.success(grainRecommendationService.generateSuggestedQuestions(skill));
    }

    /**
     * 获取所有分身列表（管理员/首页/C端我的分身）
     */
    @GetMapping("/list")
    public ApiResponse<Map<String, Object>> listSkills(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String type) {
        UUID companyId = TokenContext.getCompanyId();
        String role = extractRole();
        return ApiResponse.success(skillService.listAllSkills(page, size, status, userId, companyId, role, type));
    }

    /**
     * 我的分身列表 — 严格只看当前用户自己的分身（零角色分支）。
     * 平台端"我的分身"页面调用。
     */
    @GetMapping("/my")
    public ApiResponse<Map<String, Object>> listMySkills(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status) {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        return ApiResponse.success(skillService.listMySkills(page, size, status, userId));
    }

    /**
     * 分身详情 — System B 聊天页入口。
     * 返回头像、姓名、职级、开场白、场景标签、颗粒数等完整信息。
     */
    @GetMapping("/{skillId}/detail")
    public ApiResponse<Map<String, Object>> getSkillDetail(@PathVariable String skillId) {
        return ApiResponse.success(skillService.getSkillDetail(skillId));
    }

    /**
     * 更新分身状态。
     * B端管理员：可改任意状态（审核流水线）。
     * C端用户：只能对自己的分身操作 published（发布）。
     */
    @PutMapping("/{skillId}/status")
    public ApiResponse<Void> updateSkillStatus(
            @PathVariable String skillId,
            @RequestBody Map<String, String> body) {
        String newStatus = body.getOrDefault("status", "active");
        String role = extractRole();
        if ("c_user".equalsIgnoreCase(role)) {
            // C端：只能发布自己的分身
            Skill skill = skillRepository.findById(UUID.fromString(skillId))
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));
            Space space = spaceRepository.findById(skill.getSpaceId())
                .orElseThrow(() -> new BusinessException(404, "空间不存在"));
            if (!space.isOwnedBy(extractUserId())) {
                throw new BusinessException(403, "无权操作");
            }
            if (!"published".equals(newStatus)) {
                throw new BusinessException(400, "只能发布");
            }
        }
        skillService.updateSkillStatus(skillId, newStatus);

        // C端发布时自动创建对外分享 + 审计日志
        if ("c_user".equalsIgnoreCase(extractRole()) && "published".equals(newStatus)) {
            shareService.initDefaultShares(UUID.fromString(skillId), extractUserId(), true);
            try {
                auditLogRepository.save(com.aiextract.model.AdminAuditLog.builder()
                    .id(UUID.randomUUID())
                    .adminId(extractUserId())
                    .action("publish_skill_self")
                    .targetType("skill")
                    .targetId(UUID.fromString(skillId))
                    .detail(objectMapper.writeValueAsString(Map.of("source", "c_user")))
                    .createdAt(java.time.LocalDateTime.now())
                    .build());
            } catch (Exception e) {
                log.warn("C端发布审计日志写入失败 skillId={}", skillId, e);
            }
        }
        return ApiResponse.success();
    }

    /**
     * 分身分享：生成或获取已有分享（分身所有者/管理员可用）
     */
    @PostMapping("/{skillId}/share")
    public ApiResponse<Map<String, Object>> getOrCreateShare(@PathVariable UUID skillId,
            @RequestBody(required = false) Map<String, String> body) {
        String channel = body != null ? body.getOrDefault("channel", SkillShare.CHANNEL_PUBLIC) : SkillShare.CHANNEL_PUBLIC;
        var share = shareService.getOrCreateShare(skillId, extractUserId(), channel);
        return ApiResponse.success(toShareMap(share));
    }

    /**
     * 分身分享：启停开关（分身所有者/管理员可用）
     */
    @PutMapping("/{skillId}/share")
    public ApiResponse<Map<String, Object>> toggleShare(
            @PathVariable UUID skillId, @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        var share = shareService.toggleShare(skillId, enabled);
        return ApiResponse.success(toShareMap(share));
    }

    /**
     * 分身分享：查询分享（未生成时 404）
     */
    @GetMapping("/{skillId}/share")
    public ApiResponse<Map<String, Object>> getShare(@PathVariable UUID skillId) {
        var share = shareService.findShare(skillId)
                .orElseThrow(() -> new BusinessException(404, "尚未生成分享链接"));
        return ApiResponse.success(toShareMap(share));
    }

    /** 推荐问题 — 基于活跃颗粒的模板化生成 */
    @GetMapping("/{skillId}/recommended-questions")
    public ApiResponse<List<String>> recommendedQuestions(
            @PathVariable UUID skillId,
            @RequestParam(defaultValue = "") String sceneTag) {
        if (!sceneTag.isBlank()) {
            return ApiResponse.success(practiceDemoService.generateRecommendedQuestions(sceneTag));
        }
        Skill skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null) {
            return ApiResponse.success(List.of());
        }
        // 组织分身 → 委托 OrganizationSkillService 聚合成员场景标签
        if ("organization".equals(skill.getType())) {
            return ApiResponse.success(orgSkillService.getRecommendedQuestionsFallback(skillId));
        }
        // 优先读缓存（发布时 @Async 预生成的 JSONB 数组）
        List<String> cached = JsonUtil.parseStringList(skill.getRecommendedQuestions());
        if (!cached.isEmpty()) {
            return ApiResponse.success(cached);
        }
        // 缓存缺失 → 回退 Service 层模板生成
        return ApiResponse.success(practiceDemoService.generateRecommendedQuestionsForSkill(skillId));
    }

    /**
     * 获取分身场景标签
     *
     * <p>
     * 从experience_grain表按scene_tag分组统计，
     * 返回该分身擅长的领域列表，用于前端开场区展示。
     * </p>
     */
    @GetMapping("/{skillId}/scene-tags")
    public ApiResponse<List<Map<String, Object>>> getSceneTags(@PathVariable String skillId) {
        return ApiResponse.success(skillService.getSceneTags(skillId));
    }

    /**
     * 获取对练场景列表
     *
     * <p>
     * 从experience_grain表提取真实案例构建对练场景，
     * 替代前端硬编码的预设场景。
     * </p>
     */
    @GetMapping("/{skillId}/practice-scenes")
    public ApiResponse<List<Map<String, Object>>> getPracticeScenes(@PathVariable String skillId) {
        return ApiResponse.success(skillService.getPracticeScenes(skillId));
    }

    /**
     * 对练综合评价（SSE流式）
     *
     * <p>
     * 对练结束后，以销冠分身视角对完整对话进行复盘评价。
     * 接收完整对话记录和场景描述，流式返回结构化评价JSON。
     * </p>
     */
    @PostMapping(value = "/{skillId}/practice/evaluate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter evaluatePractice(
            @PathVariable String skillId,
            @RequestBody Map<String, String> body) {
        return SseAdapter.fromFlux(
                chatStreamService.evaluatePractice(skillId,
                        body.getOrDefault("conversation", ""),
                        body.getOrDefault("scene", ""),
                        extractUserId()));
    }

    /**
     * 查询用户对练评分趋势 — 按时间返回历次对练评估分数。
     */
    @GetMapping("/{skillId}/practice/trend")
    public ApiResponse<List<Map<String, Object>>> getPracticeTrend(
            @PathVariable String skillId) {
        UUID userId = extractUserId();
        var evaluations = skillService.getPracticeScoreTrend(skillId, userId);
        return ApiResponse.success(evaluations);
    }

    /**
     * 获取分身下所有颗粒（C端审核用）。
     * 按 spaceId 查，覆盖访谈产生和素材直传产生的颗粒。
     */
    @GetMapping("/{skillId}/grains")
    public ApiResponse<List<Map<String, Object>>> getSkillGrains(@PathVariable String skillId) {
        Skill skill = skillRepository.findById(UUID.fromString(skillId))
            .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));
        UUID userId = extractUserId();
        Space space = spaceRepository.findById(skill.getSpaceId())
            .orElseThrow(() -> new BusinessException(404, "空间不存在"));
        if (!space.isOwnedBy(userId)) {
            throw new BusinessException(403, "无权访问");
        }
        List<com.aiextract.model.ExperienceGrain> grainList = grainRepository.findBySpaceId(skill.getSpaceId());

        // 批量预取素材名称，避免 N+1
        Set<UUID> materialIds = grainList.stream()
            .map(com.aiextract.model.ExperienceGrain::getSourceMaterialId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<UUID, SkillMaterial> materialMap = materialIds.isEmpty()
            ? Map.of()
            : skillMaterialRepository.findAllById(materialIds).stream()
                .collect(Collectors.toMap(SkillMaterial::getId, Function.identity()));

        List<Map<String, Object>> grains = grainList.stream()
            .map(g -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", g.getId().toString());
                m.put("sceneTag", g.getSceneTag());
                m.put("sceneDescription", g.getSceneDescription());
                m.put("expertThought", g.getExpertThought());
                m.put("standardScript", g.getStandardScript());
                m.put("commonMistakes", g.getCommonMistakes());
                m.put("status", g.getStatus());
                m.put("qualityScore", g.getQualityScore());
                m.put("verificationNotes", g.getVerificationNotes());
                m.put("sourceType", g.getSourceType());
                if (g.getSourceMaterialId() != null) {
                    m.put("sourceMaterialId", g.getSourceMaterialId().toString());
                    SkillMaterial mat = materialMap.get(g.getSourceMaterialId());
                    if (mat != null) {
                        m.put("sourceMaterialName", mat.getFileName());
                    }
                }
                return m;
            }).toList();
        return ApiResponse.success(grains);
    }

    /**
     * 上传分身头像。
     */
    @PostMapping("/{skillId}/avatar")
    public ApiResponse<Map<String, String>> uploadAvatar(
            @PathVariable String skillId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.success(skillService.uploadAvatar(skillId, file));
    }

}
