package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.common.ErrorMessages;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.service.AdminGrainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Admin 颗粒管理 Controller — 只做参数校验和路由，业务逻辑在 AdminGrainService。
 */
@Slf4j
@RestController
@RequestMapping("/admin/grains")
@RequiredArgsConstructor
public class AdminGrainController {

    private final ExperienceGrainRepository grainRepository;
    private final SkillRepository skillRepository;
    private final AdminGrainService adminGrainService;
    private final CompanyScopeService companyScopeService;
    private final com.aiextract.repository.SkillMaterialRepository skillMaterialRepository;
    private final com.aiextract.repository.ReportRepository reportRepository;
    private final com.aiextract.repository.GrainRetrieveLogRepository grainRetrieveLogRepository;
    private final com.aiextract.repository.FeedbackLogRepository feedbackLogRepository;

    /** 颗粒列表 — 按分身空间查询（分页） */
    @GetMapping
    public ApiResponse<Map<String, Object>> listGrains(
            @RequestParam UUID skillId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "helpful") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        companyScopeService.assertSkillOwnership(skillId);
        var skill = skillRepository.findById(skillId)
            .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));

        var pageable = org.springframework.data.domain.PageRequest.of(page - 1, size,
            sort.equals("unhelpful") ? org.springframework.data.domain.Sort.by("unhelpfulCount").descending()
            : sort.equals("recent") ? org.springframework.data.domain.Sort.by("createdAt").descending()
            : org.springframework.data.domain.Sort.by("helpfulCount").descending());
        var grainPage = (status == null || status.isBlank())
            ? grainRepository.findBySpaceId(skill.getSpaceId(), pageable)
            : grainRepository.findBySpaceIdAndStatus(skill.getSpaceId(), status, pageable);

        return ApiResponse.success(PageResponse.of(
            grainPage.getContent().stream().map(this::toMap).collect(Collectors.toList()),
            grainPage, page, size));
    }

    /** 颗粒详情 */
    @GetMapping("/{grainId}")
    public ApiResponse<Map<String, Object>> getGrain(@PathVariable UUID grainId) {
        companyScopeService.assertGrainOwnership(grainId);
        ExperienceGrain g = grainRepository.findById(grainId)
            .orElseThrow(() -> new BusinessException(404, "颗粒不存在"));
        return ApiResponse.success(toMap(g));
    }

    /** 编辑颗粒 — 委托 Service 处理 */
    @PutMapping("/{grainId}")
    public ApiResponse<Map<String, Object>> updateGrain(
            @PathVariable UUID grainId, @RequestBody Map<String, String> body) {
        companyScopeService.assertGrainOwnership(grainId);
        ExperienceGrain g = adminGrainService.updateGrain(grainId, body);
        return ApiResponse.success(toMap(g));
    }

    /** 新增颗粒 — 委托 Service 处理 */
    @PostMapping
    public ApiResponse<Map<String, Object>> createGrain(@RequestBody Map<String, Object> body) {
        Object rawSkillId = body.get("skillId");
        if (!(rawSkillId instanceof String skillIdStr) || skillIdStr.isBlank()) {
            throw new BusinessException(400, "缺少 skillId");
        }
        UUID skillId;
        try {
            skillId = UUID.fromString(skillIdStr);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "skillId 格式不正确");
        }
        companyScopeService.assertSkillOwnership(skillId);
        ExperienceGrain g = adminGrainService.createGrain(body);
        return ApiResponse.success(toMap(g));
    }

    /** 恢复已废弃颗粒（重新参与检索） */
    @PostMapping("/{grainId}/restore")
    public ApiResponse<Void> restoreGrain(@PathVariable UUID grainId) {
        companyScopeService.assertGrainOwnership(grainId);
        ExperienceGrain g = grainRepository.findById(grainId)
            .orElseThrow(() -> new BusinessException(404, "颗粒不存在"));
        if (!"deprecated".equals(g.getStatus())) {
            throw new BusinessException(400, "只有已废弃的颗粒可以恢复");
        }
        adminGrainService.restoreGrain(grainId);
        return ApiResponse.success(null);
    }

    /** 废弃颗粒 */
    @PostMapping("/{grainId}/deprecate")
    public ApiResponse<Void> deprecateGrain(@PathVariable UUID grainId) {
        companyScopeService.assertGrainOwnership(grainId);
        adminGrainService.deprecateGrain(grainId);
        return ApiResponse.success(null);
    }

    private Map<String, Object> toMap(ExperienceGrain g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId().toString());
        m.put("spaceId", g.getSpaceId() != null ? g.getSpaceId().toString() : null);
        m.put("sceneTag", g.getSceneTag());
        m.put("sceneDescription", g.getSceneDescription());
        m.put("expertThought", g.getExpertThought());
        m.put("standardScript", g.getStandardScript());
        m.put("commonMistakes", g.getCommonMistakes());
        m.put("applicableCondition", g.getApplicableCondition());
        m.put("weight", g.getWeight());
        m.put("status", g.getStatus());
        m.put("helpfulCount", g.getHelpfulCount());
        m.put("unhelpfulCount", g.getUnhelpfulCount());
        m.put("qualityScore", g.getQualityScore());
        m.put("createdAt", g.getCreatedAt() != null ? g.getCreatedAt().toString() : null);
        return m;
    }

    /**
     * 溯源聚合 — 根据 grainIds 批量返回颗粒详情+报告+原始对话片段。
     */
    @GetMapping("/traceability")
    public ApiResponse<List<Map<String, Object>>> getTraceability(@RequestParam String grainIds) {
        List<UUID> ids;
        try {
            ids = Arrays.stream(grainIds.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(UUID::fromString).toList();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "grainIds 格式不正确");
        }
        if (ids.size() > 50) {
            throw new BusinessException(400, "一次最多查询 50 个颗粒");
        }
        if (ids.isEmpty()) {
            return ApiResponse.success(List.of());
        }

        // 批量取颗粒 + 批量取报告/素材，替代逐 ID findById 的 N+1
        Map<UUID, ExperienceGrain> grainMap = grainRepository.findAllById(ids).stream()
            .collect(Collectors.toMap(ExperienceGrain::getId, g -> g));
        // 平台视角（未切换企业）不限制；企业视角用当前企业的空间集合做白名单
        boolean unrestricted = companyScopeService.isUnrestrictedDataScope();
        Set<UUID> allowedSpaceIds = unrestricted ? null
                : companyScopeService.getSpaceIds(com.aiextract.config.TokenContext.getCompanyId());
        Map<UUID, com.aiextract.model.Report> reportMap = new LinkedHashMap<>();
        Map<UUID, com.aiextract.model.SkillMaterial> materialMap = new LinkedHashMap<>();
        List<UUID> reportIds = new ArrayList<>();
        List<UUID> materialIds = new ArrayList<>();
        for (UUID gid : ids) {
            var g = grainMap.get(gid);
            if (g == null) continue;
            if (!unrestricted && (g.getSpaceId() == null || !allowedSpaceIds.contains(g.getSpaceId()))) {
                throw new BusinessException(403, "无权访问该颗粒");
            }
            if (g.getReportId() != null) reportIds.add(g.getReportId());
            if (g.getSourceMaterialId() != null) materialIds.add(g.getSourceMaterialId());
        }
        if (!reportIds.isEmpty()) {
            reportRepository.findAllById(reportIds).forEach(r -> reportMap.put(r.getId(), r));
        }
        if (!materialIds.isEmpty()) {
            skillMaterialRepository.findAllById(materialIds).forEach(m -> materialMap.put(m.getId(), m));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (UUID gid : ids) {
            var grain = grainMap.get(gid);
            if (grain == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("grainId", gid.toString());
            item.put("spaceId", grain.getSpaceId() != null ? grain.getSpaceId().toString() : null);
            item.put("sceneDescription", grain.getSceneDescription());
            item.put("expertThought", grain.getExpertThought());
            item.put("standardScript", grain.getStandardScript());
            item.put("commonMistakes", grain.getCommonMistakes());
            item.put("qualityScore", grain.getQualityScore());
            item.put("difficultyLevel", grain.getDifficultyLevel());
            if (grain.getReportId() != null) {
                var r = reportMap.get(grain.getReportId());
                if (r != null) {
                    item.put("reportTitle", r.getTitle());
                    item.put("reportId", r.getId().toString());
                }
            }
            if (grain.getSourceMaterialId() != null) {
                var m = materialMap.get(grain.getSourceMaterialId());
                if (m != null) {
                    item.put("sourceName", m.getFileName());
                    item.put("sourceType", "file_upload");
                    String content = m.getParsedContent();
                    item.put("sourceSnippet", content != null && content.length() > 500
                        ? content.substring(0, 500) + "…" : content);
                }
            }
            result.add(item);
        }
        return ApiResponse.success(result);
    }

    /**
     * 颗粒诊断 —— 返回颗粒详情 + 检索历史 + 反馈上下文。
     * 管理员从排行榜点击颗粒后，一眼看清该颗粒的使用质量。
     */
    @GetMapping("/{grainId}/diagnostics")
    public ApiResponse<Map<String, Object>> getGrainDiagnostics(@PathVariable String grainId) {
        UUID id = UUID.fromString(grainId);
        ExperienceGrain grain = grainRepository.findById(id)
            .orElseThrow(() -> new BusinessException(404, "颗粒不存在"));
        companyScopeService.assertGrainOwnership(id);

        Map<String, Object> result = new LinkedHashMap<>();

        result.put("id", grain.getId().toString());
        result.put("spaceId", grain.getSpaceId().toString());
        result.put("sceneTag", grain.getSceneTag());
        result.put("sceneDescription", grain.getSceneDescription());
        result.put("expertThought", grain.getExpertThought());
        result.put("standardScript", grain.getStandardScript());
        result.put("commonMistakes", grain.getCommonMistakes());
        result.put("applicableCondition", grain.getApplicableCondition());
        result.put("weight", grain.getWeight());
        result.put("status", grain.getStatus());
        result.put("helpfulCount", grain.getHelpfulCount());
        result.put("unhelpfulCount", grain.getUnhelpfulCount());
        result.put("qualityScore", grain.getQualityScore());
        result.put("createdAt", grain.getCreatedAt() != null ? grain.getCreatedAt().toString() : null);

        var retrievals = grainRetrieveLogRepository
            .findByGrainIdOrderByCreatedAtDesc(id, org.springframework.data.domain.PageRequest.of(0, 20));
        List<Map<String, Object>> retrievalList = retrievals.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId().toString());
            m.put("conversationId", r.getConversationId().toString());
            m.put("originalQuery", r.getOriginalQuery());
            m.put("rewrittenQuery", r.getRewrittenQuery());
            m.put("similarity", r.getSimilarity());
            m.put("tier", r.getTier());
            m.put("position", r.getPosition());
            m.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
            return m;
        }).collect(Collectors.toList());
        result.put("retrievals", retrievalList);

        var feedbacks = feedbackLogRepository
            .findByGrainIdOrderByCreatedAtDesc(id, org.springframework.data.domain.PageRequest.of(0, 10));
        List<Map<String, Object>> feedbackList = feedbacks.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId().toString());
            m.put("conversationId", f.getConversationId() != null ? f.getConversationId().toString() : null);
            m.put("rating", f.getRating());
            m.put("query", f.getQuery());
            m.put("aiResponse", f.getAiResponse());
            m.put("ragScore", f.getRagScore());
            m.put("createdAt", f.getCreatedAt() != null ? f.getCreatedAt().toString() : null);
            return m;
        }).collect(Collectors.toList());
        result.put("feedbacks", feedbackList);

        return ApiResponse.success(result);
    }
}
