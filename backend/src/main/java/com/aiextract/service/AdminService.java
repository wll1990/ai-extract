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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminService {

    private final SpaceRepository spaceRepository;
    private final ExperienceGrainRepository grainRepository;
    private final ReportRepository reportRepository;
    private final SkillRepository skillRepository;
    private final SkillMaterialRepository materialRepository;
    private final UserRepository userRepository;
    private final AdminInsightService insightService;
    private final ConversationStatsRepository convStatsRepository;
    private final CompanyRepository companyRepository;

    /** 企业范围 — 一次 DB 计算，getDashboard 和 getDashboardV2 共享 */
    private record CompanyScope(List<UUID> spaceIds, List<UUID> skillIds) {
        boolean isScoped() { return spaceIds != null; }
        boolean hasSkills() { return skillIds != null && !skillIds.isEmpty(); }
    }

    private CompanyScope resolveCompanyScope(UUID companyId) {
        if (companyId == null) return new CompanyScope(null, null);
        List<UUID> userIds = userRepository.findByCompanyId(companyId).stream()
                .map(com.aiextract.model.User::getId).toList();
        List<UUID> spaceIds = userIds.isEmpty() ? List.of()
                : spaceRepository.findByUserIdIn(userIds).stream().map(Space::getId).toList();
        List<UUID> skillIds = spaceIds.isEmpty()
                ? List.of()
                : skillRepository.findBySpaceIdIn(spaceIds).stream().map(Skill::getId).toList();
        return new CompanyScope(spaceIds, skillIds);
    }

    /**
     * 工作台数据总览。companyId 非 null 时按企业隔离统计。
     */
    public Map<String, Object> getDashboard(UUID companyId) {
        CompanyScope scope = resolveCompanyScope(companyId);
        Map<String, Object> data = new LinkedHashMap<>();

        // 汇总统计 — DB 层聚合
        if (scope.isScoped() && !scope.spaceIds.isEmpty()) {
            long grainTotal = grainRepository.countBySpaceIdIn(scope.spaceIds).stream()
                    .mapToLong(row -> (Long) row[1]).sum();
            long reportTotal = reportRepository.countBySpaceIdIn(scope.spaceIds).stream()
                    .mapToLong(row -> (Long) row[1]).sum();
            long materialTotal = scope.hasSkills() ? materialRepository.countBySkillIdIn(scope.skillIds) : 0;
            data.put("stats", Map.of(
                    "spaceCount", scope.spaceIds.size(),
                    "reportCount", reportTotal,
                    "grainCount", grainTotal,
                    "materialCount", materialTotal));
        } else {
            data.put("stats", Map.of(
                    "spaceCount", spaceRepository.count(),
                    "reportCount", reportRepository.count(),
                    "grainCount", grainRepository.count(),
                    "materialCount", materialRepository.count()));
        }

        // 待审核 + 生成中
        List<String> pendingStatuses = List.of("reviewing", "generating");
        List<Skill> reviewingSkills = scope.isScoped()
                ? skillRepository.findByStatusInAndSpaceIdIn(pendingStatuses, scope.spaceIds)
                : skillRepository.findByStatusIn(pendingStatuses);
        Map<UUID, Space> spaceMap = reviewingSkills.isEmpty() ? Collections.emptyMap()
                : spaceRepository.findAllById(reviewingSkills.stream().map(Skill::getSpaceId).distinct().toList()).stream()
                    .collect(Collectors.toMap(Space::getId, s -> s, (a, b) -> a));
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Skill sk : reviewingSkills) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "skill_review");
            item.put("skillId", sk.getId().toString());
            item.put("name", sk.getOwnerName() != null ? sk.getOwnerName()
                    : sk.getDisplayName() != null ? sk.getDisplayName() : "未命名");
            item.put("status", "reviewing".equals(sk.getStatus()) ? "待审核" : "萃取中");
            Space sp = spaceMap.get(sk.getSpaceId());
            item.put("spaceId", sp != null ? sp.getId().toString() : "");
            pending.add(item);
        }

        // 素材处理中
        List<SkillMaterial> processingMaterials = scope.hasSkills()
                ? materialRepository.findByStatusInAndSkillIdIn(List.of("cleaning", "analyzing"), scope.skillIds)
                : scope.isScoped() ? List.of()
                : materialRepository.findByStatusIn(List.of("cleaning", "analyzing"));
        if (!processingMaterials.isEmpty()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "material_processing");
            item.put("count", processingMaterials.size());
            item.put("status", "素材处理中");
            pending.add(item);
        }
        data.put("pending", pending);

        // 最近活动
        List<Report> recentReports = scope.isScoped()
                ? reportRepository.findBySpaceIdInOrderByCreatedAtDesc(scope.spaceIds, PageRequest.of(0, 5))
                : reportRepository.findAll(PageRequest.of(0, 5, Sort.by("createdAt").descending())).getContent();
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

    /** 场景覆盖 — 按企业范围过滤，DB 层过滤替代全表扫描 */
    public Map<String, Object> getSceneCoverage(UUID companyId) {
        List<com.aiextract.model.ExperienceGrain> allGrains;
        if (companyId != null) {
            List<UUID> spaceIds = userRepository.findByCompanyId(companyId).stream()
                    .flatMap(u -> spaceRepository.findByUserId(u.getId()).stream())
                    .map(Space::getId).collect(Collectors.toList());
            allGrains = spaceIds.isEmpty() ? List.of() : grainRepository.findAllBySpaceIdIn(spaceIds);
        } else {
            allGrains = grainRepository.findAll();
        }
        Map<String, List<com.aiextract.model.ExperienceGrain>> grouped = allGrains.stream()
                .filter(g -> g.getSceneTag() != null)
                .collect(Collectors.groupingBy(com.aiextract.model.ExperienceGrain::getSceneTag));

        Set<String> allTags = new LinkedHashSet<>(com.aiextract.common.StatusConstants.SCENE_TAGS);
        allTags.addAll(grouped.keySet());

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

    /** 工作台 v2 — 运营指挥中心。共享 CompanyScope 避免重复查询。 */
    public Map<String, Object> getDashboardV2(UUID companyId, int days) {
        CompanyScope scope = resolveCompanyScope(companyId);
        Map<String, Object> data = new LinkedHashMap<>();

        // 1. 基础统计（复用 getDashboard，scope 已缓存不再重复计算）
        Map<String, Object> base = getDashboard(companyId);
        data.put("stats", base.get("stats"));
        data.put("pending", base.get("pending"));
        data.put("recent", base.get("recent"));

        // 2. 分身健康度
        Map<String, Object> overview = insightService.getGlobalOverview(companyId);
        data.put("skills", overview.getOrDefault("skills", List.of()));
        data.put("satisfactionRate", overview.getOrDefault("satisfactionRate", 0));
        data.put("hitRate", overview.getOrDefault("hitRate", 0));
        data.put("totalConversations", overview.getOrDefault("totalConversations", 0));

        // 3. 今日活动 + 趋势 + 管道漏斗 + 团队活跃（共用 scope.skillIds）
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        List<UUID> skillIdsForQuery = scope.isScoped()
                ? (scope.hasSkills() ? scope.skillIds : List.of())
                : skillRepository.findByStatus("published").stream().map(Skill::getId).toList();

        // N 天趋势（默认 7，可切换为 30）
        int trendDays = Math.max(1, Math.min(365, days));
        LocalDateTime weekStart = LocalDate.now().minusDays(trendDays).atStartOfDay();
        List<Map<String, Object>> trend = new ArrayList<>();
        List<Object[]> trendRows = skillIdsForQuery.isEmpty()
                ? List.of()
                : convStatsRepository.dailyTrend(skillIdsForQuery, weekStart);
        for (Object[] tr : trendRows) {
            trend.add(Map.of("date", tr[0].toString().substring(0, 10), "count", (Long) tr[1]));
        }
        data.put("trend", trend);

        // 企业排行（仅 super_admin，companyId == null）
        if (companyId == null) {
            List<Map<String, Object>> enterprises = new ArrayList<>();
            var companyUserRows = userRepository.findAll().stream()
                    .filter(u -> u.getCompanyId() != null)
                    .collect(Collectors.groupingBy(
                            com.aiextract.model.User::getCompanyId, Collectors.counting()))
                    .entrySet().stream()
                    .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                    .limit(10).toList();
            Map<UUID, String> companyNames = new HashMap<>();
            companyRepository.findAllById(companyUserRows.stream().map(Map.Entry::getKey).toList())
                    .forEach(c -> companyNames.put(c.getId(), c.getName()));
            for (var entry : companyUserRows) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("companyId", entry.getKey().toString());
                e.put("companyName", companyNames.getOrDefault(entry.getKey(), "未知"));
                e.put("userCount", entry.getValue());
                enterprises.add(e);
            }
            data.put("enterprises", enterprises);
            data.put("enterpriseCount", enterprises.size());
        }
        if (!skillIdsForQuery.isEmpty()) {
            List<Object[]> todayRows = convStatsRepository.todayActivity(skillIdsForQuery, todayStart);
            if (!todayRows.isEmpty() && todayRows.get(0) != null) {
                Object[] row = todayRows.get(0);
                data.put("today", Map.of(
                        "conversations", row[0] != null ? (Long) row[0] : 0,
                        "users", row[1] != null ? (Long) row[1] : 0));
            } else {
                data.put("today", Map.of("conversations", 0, "users", 0));
            }

            List<Object[]> funnelRows = materialRepository.pipelineFunnel(skillIdsForQuery);
            Map<String, Long> pipeline = new LinkedHashMap<>();
            for (Object[] fr : funnelRows) pipeline.put((String) fr[0], (Long) fr[1]);
            data.put("pipeline", pipeline);

            List<Object[]> userRows = convStatsRepository.userActivity(skillIdsForQuery, weekStart);
            List<Map<String, Object>> activeUsers = new ArrayList<>();
            if (!userRows.isEmpty()) {
                List<UUID> userIds = userRows.stream().map(r -> (UUID) r[0]).filter(Objects::nonNull).distinct().toList();
                Map<UUID, String> nameMap = new HashMap<>();
                userRepository.findAllById(userIds).forEach(u -> nameMap.put(u.getId(), u.getName()));
                for (Object[] ur : userRows) {
                    if (ur[0] == null) continue;
                    Map<String, Object> u = new LinkedHashMap<>();
                    UUID uid = (UUID) ur[0];
                    u.put("userId", uid.toString());
                    u.put("name", nameMap.getOrDefault(uid, "未知"));
                    u.put("conversations", (Long) ur[1]);
                    activeUsers.add(u);
                }
            }
            data.put("activeUsers", activeUsers);
        } else {
            data.put("today", Map.of("conversations", 0, "users", 0));
            data.put("pipeline", Map.of());
            data.put("activeUsers", List.of());
        }

        return data;
    }
}
