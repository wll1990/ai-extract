package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.GrainCorrectionSuggestion;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.GrainCorrectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 颗粒纠错：员工提交建议（/skills/**），管理员审核（/admin/grain-corrections/**）。
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
@RestController
@RequiredArgsConstructor
public class GrainCorrectionController {

    private final GrainCorrectionService service;
    private final SkillRepository skillRepository;
    private final UserRepository userRepository;
    private final ExperienceGrainRepository grainRepository;

    /** 员工提交纠错建议 */
    @PostMapping("/skills/{skillId}/grains/{grainId}/corrections")
    public ApiResponse<Map<String, Object>> submit(@PathVariable UUID skillId,
                                                   @PathVariable UUID grainId,
                                                   @RequestBody Map<String, String> body) {
        GrainCorrectionSuggestion s = service.submit(skillId, grainId, body);
        return ApiResponse.success(Map.of(
                "id", s.getId().toString(),
                "status", s.getStatus()));
    }

    /** 管理端列表 */
    @GetMapping("/admin/grain-corrections")
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        Page<GrainCorrectionSuggestion> p = service.list(status, page, size);
        List<GrainCorrectionSuggestion> rows = p.getContent();
        Map<UUID, String> skillNames = new HashMap<>();
        skillRepository.findAllById(rows.stream().map(GrainCorrectionSuggestion::getSkillId)
                .filter(Objects::nonNull).distinct().toList())
                .forEach(s -> skillNames.put(s.getId(), s.getDisplayName() != null
                        ? s.getDisplayName() : s.getOwnerName()));
        Map<UUID, String> userNames = new HashMap<>();
        userRepository.findAllById(rows.stream().map(GrainCorrectionSuggestion::getSuggestedBy)
                .filter(Objects::nonNull).distinct().toList())
                .forEach(u -> userNames.put(u.getId(), u.getName()));
        Map<UUID, ExperienceGrain> grains = new HashMap<>();
        grainRepository.findAllById(rows.stream().map(GrainCorrectionSuggestion::getGrainId)
                .filter(Objects::nonNull).distinct().toList())
                .forEach(g -> grains.put(g.getId(), g));

        List<Map<String, Object>> content = rows.stream().map(s -> {
            Map<String, Object> m = toMap(s);
            m.put("skillName", skillNames.get(s.getSkillId()));
            m.put("suggestedByName", userNames.get(s.getSuggestedBy()));
            ExperienceGrain g = grains.get(s.getGrainId());
            m.put("grainSceneTag", g != null ? g.getSceneTag() : null);
            m.put("grainSceneDescription", g != null ? g.getSceneDescription() : null);
            return m;
        }).toList();
        return ApiResponse.success(PageResponse.of(content, p, page, size));
    }

    /** 待审数量 */
    @GetMapping("/admin/grain-corrections/pending-count")
    public ApiResponse<Map<String, Object>> pendingCount() {
        return ApiResponse.success(Map.of("count", service.pendingCount()));
    }

    @PostMapping("/admin/grain-corrections/{id}/approve")
    public ApiResponse<Map<String, Object>> approve(@PathVariable UUID id,
                                                    @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        return ApiResponse.success(toMap(service.approve(id, note)));
    }

    @PostMapping("/admin/grain-corrections/{id}/reject")
    public ApiResponse<Map<String, Object>> reject(@PathVariable UUID id,
                                                   @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        return ApiResponse.success(toMap(service.reject(id, note)));
    }

    private Map<String, Object> toMap(GrainCorrectionSuggestion s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId().toString());
        m.put("grainId", s.getGrainId().toString());
        m.put("skillId", s.getSkillId().toString());
        m.put("suggestedBy", s.getSuggestedBy().toString());
        m.put("reason", s.getReason());
        m.put("sceneDescription", s.getSceneDescription());
        m.put("expertThought", s.getExpertThought());
        m.put("standardScript", s.getStandardScript());
        m.put("commonMistakes", s.getCommonMistakes());
        m.put("status", s.getStatus());
        m.put("reviewedBy", s.getReviewedBy() != null ? s.getReviewedBy().toString() : null);
        m.put("reviewedAt", s.getReviewedAt() != null ? s.getReviewedAt().toString() : null);
        m.put("reviewNote", s.getReviewNote());
        m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        return m;
    }
}
