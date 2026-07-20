package com.aiextract.service;

import com.aiextract.model.Report;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillMaterial;
import com.aiextract.model.Space;
import com.aiextract.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
/**
 * @author AI Extract Team
 */
@RequiredArgsConstructor
public class AdminService {

    private final SpaceRepository spaceRepository;
    private final ExperienceGrainRepository grainRepository;
    private final ReportRepository reportRepository;
    private final SkillRepository skillRepository;
    private final SkillMaterialRepository materialRepository;
    private final UserRepository userRepository;

    public Map<String, Object> getDashboard() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 汇总统计
        data.put("stats", Map.of(
                "spaceCount", spaceRepository.count(),
                "reportCount", reportRepository.count(),
                "grainCount", grainRepository.count(),
                "materialCount", materialRepository.count()));

        // 待审核 — 批量查 space，避免 N+1
        List<Skill> reviewingSkills = skillRepository.findByStatus("reviewing");
        Map<UUID, Space> spaceMap = Collections.emptyMap();
        if (!reviewingSkills.isEmpty()) {
            List<UUID> spaceIds = reviewingSkills.stream().map(Skill::getSpaceId).distinct().toList();
            spaceMap = spaceRepository.findAllById(spaceIds).stream()
                    .collect(Collectors.toMap(Space::getId, s -> s, (a, b) -> a));
        }
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Skill sk : reviewingSkills) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "skill_review");
            item.put("skillId", sk.getId().toString());
            String name = sk.getOwnerName() != null ? sk.getOwnerName()
                    : sk.getDisplayName() != null ? sk.getDisplayName() : "未命名";
            item.put("name", name);
            item.put("status", "待审核");
            Space sp = spaceMap.get(sk.getSpaceId());
            item.put("spaceId", sp != null ? sp.getId().toString() : "");
            pending.add(item);
        }

        // 素材处理中
        List<SkillMaterial> processingMaterials =
                materialRepository.findByStatusIn(List.of("cleaning", "analyzing"));
        if (!processingMaterials.isEmpty()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "material_processing");
            item.put("count", processingMaterials.size());
            item.put("status", "素材处理中");
            pending.add(item);
        }
        data.put("pending", pending);

        // 最近活动 — 批量查 space + user，避免 N+1
        List<Report> recentReports = reportRepository
                .findAll(PageRequest.of(0, 5, Sort.by("createdAt").descending()))
                .getContent();
        Map<UUID, Space> recentSpaceMap = Collections.emptyMap();
        Map<UUID, String> userNameMap = Collections.emptyMap();
        if (!recentReports.isEmpty()) {
            List<UUID> rSpaceIds = recentReports.stream()
                    .map(Report::getSpaceId).filter(Objects::nonNull).distinct().toList();
            if (!rSpaceIds.isEmpty()) {
                recentSpaceMap = spaceRepository.findAllById(rSpaceIds).stream()
                        .collect(Collectors.toMap(Space::getId, s -> s, (a, b) -> a));
                List<UUID> userIds = recentSpaceMap.values().stream()
                        .map(Space::getUserId).distinct().toList();
                userNameMap = userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(
                                com.aiextract.model.User::getId,
                                com.aiextract.model.User::getName, (a, b) -> a));
            }
        }
        List<Map<String, Object>> recent = new ArrayList<>();
        for (Report r : recentReports) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "report");
            item.put("title", r.getTitle());
            item.put("time", r.getCreatedAt() != null ? r.getCreatedAt().toString() : "");
            Space sp = r.getSpaceId() != null ? recentSpaceMap.get(r.getSpaceId()) : null;
            item.put("spaceName", sp != null ? userNameMap.getOrDefault(sp.getUserId(), "") : "");
            recent.add(item);
        }
        data.put("recent", recent);

        return data;
    }

    /** 场景覆盖 — 之前散落在 AdminController */
    public Map<String, Object> getSceneCoverage() {
        List<com.aiextract.model.ExperienceGrain> allGrains = grainRepository.findAll();
        Map<String, List<com.aiextract.model.ExperienceGrain>> grouped = allGrains.stream()
                .filter(g -> g.getSceneTag() != null)
                .collect(Collectors.groupingBy(com.aiextract.model.ExperienceGrain::getSceneTag));

        // 框架标签 + 实际数据标签
        Set<String> allTags = new LinkedHashSet<>(com.aiextract.common.StatusConstants.SCENE_TAGS);
        allTags.addAll(grouped.keySet());

        // 批量查报告评分，避免 N+1
        Set<UUID> allReportIds = allGrains.stream()
                .map(com.aiextract.model.ExperienceGrain::getReportId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Report> reportMap = reportRepository.findAllById(allReportIds).stream()
                .collect(Collectors.toMap(Report::getId, r -> r, (a, b) -> a));

        List<Map<String, Object>> scenes = new ArrayList<>();
        for (String tag : allTags) {
            List<com.aiextract.model.ExperienceGrain> grains = grouped.getOrDefault(tag, List.of());
            int count = grains.size();
            long reportCount = grains.stream()
                    .map(com.aiextract.model.ExperienceGrain::getReportId).distinct().count();
            double avgRating = grains.stream()
                    .map(g -> {
                        Report r = g.getReportId() != null ? reportMap.get(g.getReportId()) : null;
                        return r != null && r.getRating() != null ? r.getRating().doubleValue() : 0.0;
                    })
                    .filter(r -> r > 0).mapToDouble(Double::doubleValue).average().orElse(0.0);
            String coverage = reportCount >= 3 ? "sufficient" : reportCount >= 1 ? "moderate" : "empty";

            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("name", tag);
            scene.put("reportCount", (int) reportCount);
            scene.put("avgRating", Math.round(avgRating * 10.0) / 10.0);
            scene.put("coverage", coverage);
            scenes.add(scene);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenes", scenes);
        return result;
    }
}
