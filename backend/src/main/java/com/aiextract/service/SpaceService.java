package com.aiextract.service;

import com.aiextract.common.ErrorMessages;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.*;
import com.aiextract.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
/**
 * @author AI Extract Team
 */
@RequiredArgsConstructor
public class SpaceService {

    private final SpaceRepository spaceRepository;
    private final UserRepository userRepository;
    private final ReportRepository reportRepository;
    private final SkillRepository skillRepository;
    private final ToolRepository toolRepository;
    private final ExperienceGrainRepository grainRepository;
    private final InterviewSessionRepository interviewRepository;
    private final SkillMaterialRepository materialRepository;
    private final ObjectMapper objectMapper;

    /** 空间详情 — 从 SpaceController 迁移 */
    @SuppressWarnings("PMD.MethodTooLongRule")
    public Map<String, Object> getSpaceDetail(String spaceId) {
        Space s = spaceRepository.findById(UUID.fromString(spaceId))
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SPACE_NOT_FOUND));

        User owner = userRepository.findById(s.getUserId()).orElse(null);
        String ownerName = owner != null ? owner.getName() : ErrorMessages.DEFAULT_USER_NAME;
        String ownerAvatar = owner != null ? owner.getAvatarUrl() : null;
        String ownerTitle = s.getDescription() != null ? s.getDescription() : "";
        List<String> ownerTags = parseTags(s.getTags());

        // 报告列表
        List<Report> reports = reportRepository.findBySpaceIdOrderByCreatedAtDesc(
                s.getId(), PageRequest.of(0, 20)).getContent();
        List<Map<String, Object>> reportList = new ArrayList<>();
        for (Report r : reports) {
            Map<String, Object> ri = new LinkedHashMap<>();
            ri.put("id", r.getId().toString());
            ri.put("title", r.getTitle());
            ri.put("subtitle", r.getSubtitle());
            ri.put("rating", r.getRating());
            ri.put("viewCount", r.getViewCount());
            ri.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
            ri.put("stepCount", countSteps(r));
            ri.put("sceneTags", getSceneTagsForReport(r.getId()));
            reportList.add(ri);
        }

        String oneliner = reports.isEmpty() ? "" : extractOneliner(reports.get(0));

        // Skill 状态
        Skill skill = skillRepository.findBySpaceId(s.getId()).orElse(null);
        String skillStatus = skill != null ? skill.getStatus() : "";
        String skillId = skill != null ? skill.getId().toString() : null;
        int skillGrainCount = 0;

        // 锦囊统计 — 不用 findAll 加载全部，用 aggregation
        List<ExperienceGrain> allGrains = grainRepository.findBySpaceId(s.getId());
        int grainCount = allGrains.size();
        Map<String, Long> grainByScene = allGrains.stream()
                .filter(g -> g.getSceneTag() != null && !g.getSceneTag().isEmpty())
                .collect(Collectors.groupingBy(
                        ExperienceGrain::getSceneTag, LinkedHashMap::new, Collectors.counting()));
        List<Map<String, Object>> grainDistribution = grainByScene.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("tag", e.getKey());
                    item.put("count", e.getValue().intValue());
                    return item;
                }).collect(Collectors.toList());

        if (skill != null) {
            skillGrainCount = (int) allGrains.stream()
                    .filter(g -> "active".equals(g.getStatus())).count();
        }

        // 访谈统计 — "created" 仅是点击开始但未实际对话，不计入次数
        List<InterviewSession> interviews = interviewRepository.findBySpaceIdAndStatusIn(s.getId(),
                List.of("completed", "in_progress", "paused"));
        int interviewCount = interviews.size();

        // 素材统计
        int materialCount = 0;
        if (skill != null) {
            materialCount = materialRepository.findBySkillId(skill.getId()).size();
        }

        // 报告统计
        int reportCount = (int) reportRepository.findBySpaceIdOrderByCreatedAtDesc(
                s.getId(), PageRequest.of(0, 1)).getTotalElements();
        int viewCount = reports.stream().mapToInt(r -> r.getViewCount() != null ? r.getViewCount() : 0).sum();

        // 工具统计
        List<Tool> tools = toolRepository.findBySpaceId(s.getId());
        long posters = tools.stream().filter(t -> "poster".equals(t.getType())).count();
        long cards = tools.stream().filter(t -> "card".equals(t.getType())).count();
        long checklists = tools.stream().filter(t -> "checklist".equals(t.getType())).count();
        long scripts = tools.stream().filter(t -> "script".equals(t.getType())).count();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", s.getId().toString());
        data.put("ownerName", ownerName);
        data.put("ownerAvatar", ownerAvatar);
        data.put("ownerTitle", ownerTitle);
        data.put("ownerTags", ownerTags);
        data.put("oneliner", oneliner);
        data.put("title", s.getTitle());
        data.put("description", s.getDescription());
        data.put("tags", parseTags(s.getTags()));
        data.put("isPublic", s.getIsPublic());
        data.put("status", s.getStatus());
        data.put("reports", reportList);
        data.put("skillStatus", skillStatus);
        data.put("skillId", skillId);
        data.put("skillGrainCount", skillGrainCount);
        data.put("grainCount", grainCount);
        data.put("grainDistribution", grainDistribution);
        data.put("interviewCount", interviewCount);
        data.put("materialCount", materialCount);
        data.put("stats", Map.of("reportCount", reportCount, "viewCount", viewCount,
                "grainCount", grainCount, "interviewCount", interviewCount, "materialCount", materialCount));
        data.put("downloads", Map.of("posters", posters, "cards", cards, "checklists", checklists, "scripts", scripts));
        return data;
    }

    // ---- helpers ----

    private List<String> parseTags(String tagsJson) {
        try {
            if (tagsJson == null || tagsJson.isBlank()) { return List.of(); }
            return objectMapper.readValue(tagsJson, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) { return List.of(); }
    }

    private String extractOneliner(Report r) {
        try {
            var node = objectMapper.readTree(r.getContentJson());
            var quotes = node.path("chapters").get(3).path("quotes");
            return quotes.isArray() && quotes.size() > 0 ? quotes.get(0).asText() : "";
        } catch (Exception e) { return ""; }
    }

    private int countSteps(Report r) {
        try {
            var node = objectMapper.readTree(r.getContentJson());
            var steps = node.path("chapters").get(1).path("steps");
            return steps.isArray() ? steps.size() : 0;
        } catch (Exception e) { return 0; }
    }

    private List<String> getSceneTagsForReport(UUID reportId) {
        try {
            return grainRepository.findByReportId(reportId).stream()
                    .map(ExperienceGrain::getSceneTag)
                    .filter(Objects::nonNull).distinct().limit(5)
                    .collect(Collectors.toList());
        } catch (Exception e) { return List.of(); }
    }
}
