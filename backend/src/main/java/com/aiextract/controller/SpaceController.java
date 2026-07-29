package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.model.Space;
import com.aiextract.repository.SpaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.aiextract.common.ErrorMessages;
import com.aiextract.exception.BusinessException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 空间控制器（补全缺失的4-7号接口）
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@RestController
@RequestMapping("/spaces")
@RequiredArgsConstructor

public class SpaceController {

    private final SpaceRepository spaceRepository;
    private final com.aiextract.repository.UserRepository userRepository;
    private final com.aiextract.repository.ReportRepository reportRepository;
    private final com.aiextract.repository.SkillRepository skillRepository;
    private final com.aiextract.repository.ToolRepository toolRepository;
    private final com.aiextract.repository.ExperienceGrainRepository grainRepository;
    private final com.aiextract.service.SpaceService spaceService;
    private final ObjectMapper objectMapper;

    @GetMapping
    public ApiResponse<Map<String, Object>> getSpaces(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false, defaultValue = "createdAt") String sort) {
        PageRequest pr = PageRequest.of(page - 1, size);
        Page<Space> sp = (keyword != null && !keyword.isEmpty())
                ? spaceRepository.findByTitleContainingIgnoreCase(keyword, pr)
                : spaceRepository.findAll(pr);

        List<Space> spaces = sp.getContent();
        if (spaces.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of());
            result.put("page", page); result.put("size", size);
            result.put("total", 0L); result.put("totalPages", 0);
            return ApiResponse.success(result);
        }

        // 批量查询：一次查所有 reportCount + skillStatus + userName
        List<UUID> spaceIds = spaces.stream().map(Space::getId).toList();
        List<UUID> userIds = spaces.stream().map(Space::getUserId).distinct().toList();

        // 批量报告数
        Map<UUID, Long> reportCounts = reportRepository.countBySpaceIdIn(spaceIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1], (a, b) -> a));

        // 批量颗粒数
        Map<UUID, Long> grainCounts = grainRepository.countBySpaceIdIn(spaceIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1], (a, b) -> a));

        // 批量 Skill 状态
        Map<UUID, String> skillStatuses = skillRepository.findBySpaceIdIn(spaceIds).stream()
                .collect(Collectors.toMap(
                        com.aiextract.model.Skill::getSpaceId,
                        com.aiextract.model.Skill::getStatus, (a, b) -> a));

        // 批量用户名称和角色
        List<com.aiextract.model.User> owners = userRepository.findAllById(userIds);
        Map<UUID, String> userNames = owners.stream()
                .collect(Collectors.toMap(
                        com.aiextract.model.User::getId,
                        com.aiextract.model.User::getName, (a, b) -> a));
        Map<UUID, String> userRoles = owners.stream()
                .collect(Collectors.toMap(
                        com.aiextract.model.User::getId,
                        com.aiextract.model.User::getRole, (a, b) -> a));

        // 展示规则：空间总览只展示有个人空间的用户（排除管理员空间）
        // 注：此处用角色判断而非权限码，因为是数据展示范围，非权限校验
        List<Space> visibleSpaces = spaces.stream()
                .filter(s -> !"super_admin".equals(userRoles.getOrDefault(s.getUserId(), "employee")))
                .toList();

        List<Map<String, Object>> content = new ArrayList<>();
        for (Space s : visibleSpaces) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId().toString());
            item.put("ownerName", userNames.getOrDefault(s.getUserId(), "未知用户"));
            item.put("title", s.getTitle());
            item.put("description", s.getDescription());
            item.put("tags", parseTags(s.getTags()));
            item.put("isPublic", s.getIsPublic());
            item.put("reportCount", reportCounts.getOrDefault(s.getId(), 0L).intValue());
            item.put("grainCount", grainCounts.getOrDefault(s.getId(), 0L).intValue());
            item.put("skillStatus", skillStatuses.getOrDefault(s.getId(), ""));
            item.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            content.add(item);
        }

        // 客户端排序：按报告数降序（有内容的排前面）
        if ("reportCount".equals(sort)) {
            content.sort((a, b) -> Integer.compare(
                    (Integer) b.get("reportCount"), (Integer) a.get("reportCount")));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("page", page); result.put("size", size);
        result.put("total", sp.getTotalElements()); result.put("totalPages", sp.getTotalPages());
        return ApiResponse.success(result);
    }

    @GetMapping("/{spaceId}")
    public ApiResponse<Map<String, Object>> getSpace(@PathVariable String spaceId) {
        return ApiResponse.success(spaceService.getSpaceDetail(spaceId));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> createSpace(@RequestBody Map<String, Object> body) {
        LocalDateTime now = LocalDateTime.now();
        Space s = Space.builder().id(UUID.randomUUID())
                .userId(UUID.fromString((String) body.getOrDefault("userId", UUID.randomUUID().toString())))
                .title((String) body.get("title")).description((String) body.get("description"))
                .tags(toJson(body.get("tags"))).isPublic((Boolean) body.getOrDefault("isPublic", false))
                .status("active").createdAt(now).updatedAt(now).build();
        spaceRepository.save(s);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", s.getId().toString());
        data.put("title", s.getTitle());
        return ApiResponse.success(data);
    }

    @PutMapping("/{spaceId}")
    public ApiResponse<Map<String, Object>> updateSpace(@PathVariable String spaceId, @RequestBody Map<String, Object> body) {
        Space s = spaceRepository.findById(UUID.fromString(spaceId))
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SPACE_NOT_FOUND));
        { if (body.containsKey("title")) s.setTitle((String) body.get("title")); }
        { if (body.containsKey("description")) s.setDescription((String) body.get("description")); }
        { if (body.containsKey("tags")) s.setTags(toJson(body.get("tags"))); }
        { if (body.containsKey("isPublic")) s.setIsPublic((Boolean) body.get("isPublic")); }
        spaceRepository.save(s);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", s.getId().toString());
        data.put("title", s.getTitle());
        return ApiResponse.success(data);
    }

    @SuppressWarnings("unchecked")
    private List<String> parseTags(String json) {
        { if (json == null || json.isEmpty()) return List.of(); }
        try { return objectMapper.readValue(json, List.class); }
        catch (Exception e) { log.warn("JSON解析失败, json: {}", json, e); return List.of(); }
    }
    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { log.warn("JSON序列化失败", e); return "[]"; }
    }

    /** 从报告 content_json 中提取一句话信条 */
    private String extractOneliner(com.aiextract.model.Report report) {
        try {
            if (report.getContentJson() == null || report.getContentJson().isEmpty()) return "";
            com.fasterxml.jackson.databind.ObjectMapper mapper = objectMapper;
            Map<String, Object> content = mapper.readValue(report.getContentJson(), Map.class);
            Object chapters = content.get("chapters");
            if (chapters instanceof List) {
                for (Object ch : (List<?>) chapters) {
                    if (ch instanceof Map) {
                        Map<?, ?> cm = (Map<?, ?>) ch;
                        // 第四章：专家心法
                        Object quotes = cm.get("quotes");
                        if (quotes instanceof List && !((List<?>) quotes).isEmpty()) {
                            Object first = ((List<?>) quotes).get(0);
                            return first != null ? first.toString() : "";
                        }
                        Object onelinerObj = cm.get("oneliner");
                        if (onelinerObj != null) {

                            return onelinerObj.toString();

                        }
                    }
                }
            }
        } catch (Exception e) { log.debug("数据解析失败", e); }
        return "";
    }

    /** 从报告 content_json 中统计步骤数 */
    private int countSteps(com.aiextract.model.Report report) {
        try {
            if (report.getContentJson() == null) return 0;
            com.fasterxml.jackson.databind.ObjectMapper mapper = objectMapper;
            Map<String, Object> content = mapper.readValue(report.getContentJson(), Map.class);
            Object chapters = content.get("chapters");
            if (chapters instanceof List) {
                for (Object ch : (List<?>) chapters) {
                    if (ch instanceof Map) {
                        Map<?, ?> cm = (Map<?, ?>) ch;
                        Object steps = cm.get("steps");
                        if (steps instanceof List) {

                            return ((List<?>) steps).size();

                        }
                    }
                }
            }
        } catch (Exception e) { log.debug("数据解析失败", e); }
        return 0;
    }

    /** 查报告的场景标签（从锦囊中提取去重） */
    private List<String> getSceneTagsForReport(UUID reportId) {
        try {
            List<com.aiextract.model.ExperienceGrain> grains = grainRepository.findByReportId(reportId);
            return grains.stream()
                    .map(com.aiextract.model.ExperienceGrain::getSceneTag)
                    .filter(t -> t != null && !t.isEmpty())
                    .distinct()
                    .limit(5)
                    .collect(Collectors.toList());
        } catch (Exception e) { log.debug("数据解析失败", e); }
        return List.of();
    }
}
