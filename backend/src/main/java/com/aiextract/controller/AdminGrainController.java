package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.ErrorMessages;
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

    /** 颗粒列表 — 按分身空间查询 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> listGrains(
            @RequestParam UUID skillId,
            @RequestParam(defaultValue = "helpful") String sort) {
        var skill = skillRepository.findById(skillId)
            .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));

        List<ExperienceGrain> grains = grainRepository.findBySpaceId(skill.getSpaceId()).stream()
            .sorted((a, b) -> {
                if ("unhelpful".equals(sort)) return Integer.compare(b.getUnhelpfulCount(), a.getUnhelpfulCount());
                if ("recent".equals(sort)) {
                    if (a.getCreatedAt() == null) return 1;
                    if (b.getCreatedAt() == null) return -1;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                }
                return Integer.compare(b.getHelpfulCount(), a.getHelpfulCount());
            }).limit(50).collect(Collectors.toList());

        return ApiResponse.success(grains.stream().map(this::toMap).collect(Collectors.toList()));
    }

    /** 颗粒详情 */
    @GetMapping("/{grainId}")
    public ApiResponse<Map<String, Object>> getGrain(@PathVariable UUID grainId) {
        ExperienceGrain g = grainRepository.findById(grainId)
            .orElseThrow(() -> new BusinessException(404, "颗粒不存在"));
        return ApiResponse.success(toMap(g));
    }

    /** 编辑颗粒 — 委托 Service 处理 */
    @PutMapping("/{grainId}")
    public ApiResponse<Map<String, Object>> updateGrain(
            @PathVariable UUID grainId, @RequestBody Map<String, String> body) {
        ExperienceGrain g = adminGrainService.updateGrain(grainId, body);
        return ApiResponse.success(toMap(g));
    }

    /** 新增颗粒 — 委托 Service 处理 */
    @PostMapping
    public ApiResponse<Map<String, Object>> createGrain(@RequestBody Map<String, Object> body) {
        ExperienceGrain g = adminGrainService.createGrain(body);
        return ApiResponse.success(toMap(g));
    }

    /** 废弃颗粒 */
    @PostMapping("/{grainId}/deprecate")
    public ApiResponse<Void> deprecateGrain(@PathVariable UUID grainId) {
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
}
