package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.ErrorMessages;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.Permission;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Skill;
import com.aiextract.model.Space;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.service.AdminGrainService;
import com.aiextract.service.AdminInsightService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 分身主（空间属主）查看自己分身的数据洞察。
 *
 * <p>管理后台的 /admin/insights/** 需要 DASHBOARD_VIEW，员工没有该权限，
 * 但员工是自己分身的主人，应该能看到自己分身的使用数据。此控制器只读，
 * 并且把查询范围严格限制在「空间属主本人」；管理员仍走 /admin/insights/**。</p>
 */
@RestController
@RequestMapping("/skills/{skillId}/insights")
@RequiredArgsConstructor
public class SkillOwnerInsightController {

    private final SkillRepository skillRepository;
    private final SpaceRepository spaceRepository;
    private final ExperienceGrainRepository grainRepository;
    private final AdminInsightService adminInsightService;
    private final AdminGrainService adminGrainService;
    private final CompanyScopeService companyScopeService;

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> getOverview(@PathVariable String skillId) {
        UUID id = requireOwner(skillId);
        return ApiResponse.success(adminInsightService.getSkillOverview(id));
    }

    @GetMapping("/scene-top")
    public ApiResponse<List<Map<String, Object>>> getSceneTop(@PathVariable String skillId) {
        UUID id = requireOwner(skillId);
        return ApiResponse.success(adminInsightService.getSceneTop(id));
    }

    @GetMapping("/rag-distribution")
    public ApiResponse<Map<String, Object>> getRagDistribution(@PathVariable String skillId) {
        UUID id = requireOwner(skillId);
        return ApiResponse.success(adminInsightService.getRagDistribution(id));
    }

    @GetMapping("/grains-top")
    public ApiResponse<List<Map<String, Object>>> getGrainsTop(
            @PathVariable String skillId,
            @RequestParam(defaultValue = "best") String sort) {
        UUID id = requireOwner(skillId);
        return ApiResponse.success(adminInsightService.getGrainsTop(id, sort));
    }

    @GetMapping("/knowledge-gaps")
    public ApiResponse<Map<String, Object>> getKnowledgeGaps(
            @PathVariable String skillId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID id = requireOwner(skillId);
        return ApiResponse.success(adminInsightService.getKnowledgeGaps(id, page, size));
    }

    /** 分身主颗粒列表 —— 员工没有 GRAIN_MANAGE，不能用 /admin/grains */
    @GetMapping("/grains")
    public ApiResponse<Map<String, Object>> listGrains(
            @PathVariable String skillId,
            @RequestParam(defaultValue = "helpful") String sort) {
        UUID id = requireOwner(skillId);
        Skill skill = skillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));
        if (skill.getSpaceId() == null) {
            throw new BusinessException(403, "组织分身颗粒请前往管理后台查看");
        }
        Sort order = switch (sort) {
            case "unhelpful" -> Sort.by(Sort.Direction.DESC, "unhelpfulCount");
            case "recent" -> Sort.by(Sort.Direction.DESC, "createdAt");
            default -> Sort.by(Sort.Direction.DESC, "helpfulCount");
        };
        List<ExperienceGrain> grains = grainRepository
                .findBySpaceId(skill.getSpaceId(), PageRequest.of(0, 200, order)).getContent();
        List<Map<String, Object>> content = new ArrayList<>();
        for (ExperienceGrain g : grains) {
            content.add(toGrainMap(g, false));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("total", content.size());
        return ApiResponse.success(result);
    }

    @GetMapping("/grains/{grainId}")
    public ApiResponse<Map<String, Object>> getGrain(
            @PathVariable String skillId, @PathVariable String grainId) {
        return ApiResponse.success(toGrainMap(requireOwnedGrain(skillId, grainId), true));
    }

    @PutMapping("/grains/{grainId}")
    public ApiResponse<Map<String, Object>> updateGrain(
            @PathVariable String skillId,
            @PathVariable String grainId,
            @RequestBody Map<String, String> body) {
        ExperienceGrain grain = requireOwnedGrain(skillId, grainId);
        if (body.containsKey("weight")) {
            try {
                double weight = Double.parseDouble(body.get("weight"));
                if (weight < 0.1 || weight > 2.0) {
                    throw new BusinessException(400, "权重需在 0.1 - 2.0 之间");
                }
            } catch (NumberFormatException e) {
                throw new BusinessException(400, "权重格式不正确");
            }
        }
        body.putIfAbsent("editedBy", String.valueOf(TokenContext.get()));
        ExperienceGrain saved = adminGrainService.updateGrain(grain.getId(), body);
        return ApiResponse.success(toGrainMap(saved, true));
    }

    private ExperienceGrain requireOwnedGrain(String skillId, String grainId) {
        UUID skillUid = requireOwner(skillId);
        Skill skill = skillRepository.findById(skillUid)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));
        UUID grainUid;
        try {
            grainUid = UUID.fromString(grainId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "颗粒 ID 格式不正确");
        }
        ExperienceGrain grain = grainRepository.findById(grainUid)
                .orElseThrow(() -> new BusinessException(404, "颗粒不存在"));
        if (skill.getSpaceId() == null || !skill.getSpaceId().equals(grain.getSpaceId())) {
            throw new BusinessException(403, "该颗粒不属于此分身");
        }
        return grain;
    }

    private Map<String, Object> toGrainMap(ExperienceGrain g, boolean withText) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId().toString());
        m.put("sceneDescription", g.getSceneDescription());
        m.put("sceneTag", g.getSceneTag());
        m.put("weight", g.getWeight());
        m.put("status", g.getStatus());
        m.put("helpfulCount", g.getHelpfulCount());
        m.put("unhelpfulCount", g.getUnhelpfulCount());
        m.put("qualityScore", g.getQualityScore());
        m.put("createdAt", g.getCreatedAt() != null ? g.getCreatedAt().toString() : null);
        if (withText) {
            m.put("expertThought", g.getExpertThought());
            m.put("standardScript", g.getStandardScript());
            m.put("commonMistakes", g.getCommonMistakes());
            m.put("applicableCondition", g.getApplicableCondition());
        }
        return m;
    }

    /** 只有空间属主或具备看板权限的管理员可以读取 */
    private UUID requireOwner(String skillId) {
        UUID id;
        try {
            id = UUID.fromString(skillId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "分身 ID 格式不正确");
        }
        Skill skill = skillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SKILL_NOT_FOUND));

        if (companyScopeService.isSuperAdmin() || hasDashboardView()) {
            companyScopeService.assertSkillOwnership(id);
            return id;
        }
        if (skill.getSpaceId() == null) {
            throw new BusinessException(403, "组织分身数据请前往管理后台查看");
        }
        Space space = spaceRepository.findById(skill.getSpaceId())
                .orElseThrow(() -> new BusinessException(404, "空间不存在"));
        if (!space.isOwnedBy(TokenContext.get())) {
            throw new BusinessException(403, "只能查看自己分身的数据");
        }
        return id;
    }

    private boolean hasDashboardView() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> Permission.DASHBOARD_VIEW.equals(a.getAuthority()));
    }
}
