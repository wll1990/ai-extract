package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.ErrorMessages;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.Space;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

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
    private final CompanyScopeService companyScopeService;
    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    private String getToken() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getCredentials();
    }

    private UUID getCurrentUserId() {
        return jwtUtil.getUserIdFromToken(getToken());
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> getSpaces(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false, defaultValue = "createdAt") String sort) {
        // 权限判定
        boolean isAdmin = isAdmin();
        UUID currentUserId = getCurrentUserId();
        // employee 只能看自己的空间，忽略前端传入的 userId 参数
        if (!isAdmin) {
            userId = currentUserId;
        }

        // 公司隔离：非 super_admin 只看本公司用户的空间
        UUID companyId = TokenContext.getCompanyId();
        List<UUID> companyUserIds = null;
        if (companyId != null) {
            companyUserIds = userRepository.findByCompanyId(companyId).stream()
                .map(com.aiextract.model.User::getId).toList();
            if (companyUserIds.isEmpty()) {
                return ApiResponse.success(Map.of("content", List.of(), "page", page, "size", size, "total", 0L, "totalPages", 0));
            }
        }

        PageRequest pr = PageRequest.of(page - 1, size);
        Page<Space> sp;

        // userId 优先，其次 company 过滤，super_admin 全量
        List<UUID> filterUserIds;
        if (userId != null) {
            filterUserIds = List.of(userId);
        } else if (companyUserIds != null) {
            filterUserIds = companyUserIds;
        } else {
            filterUserIds = null;
        }

        if (filterUserIds != null) {
            sp = (keyword != null && !keyword.isEmpty())
                ? spaceRepository.findByTitleContainingIgnoreCaseAndUserIdIn(keyword, filterUserIds, pr)
                : spaceRepository.findByUserIdIn(filterUserIds, pr);
        } else if (keyword != null && !keyword.isEmpty()) {
            sp = spaceRepository.findByTitleContainingIgnoreCase(keyword, pr);
        } else {
            sp = spaceRepository.findAll(pr);
        }

        List<Space> spaces = sp.getContent();
        if (spaces.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", List.of());
            result.put("page", page); result.put("size", size);
            result.put("total", sp.getTotalElements()); result.put("totalPages", sp.getTotalPages());  // 用 DB 层分页数据
            return ApiResponse.success(result);
        }

        // 批量查询：reportCount + skillStatus + userName
        List<UUID> spaceIds = spaces.stream().map(Space::getId).toList();
        List<UUID> spaceUserIds = spaces.stream().map(Space::getUserId).distinct().toList();

        Map<UUID, Long> reportCounts = reportRepository.countBySpaceIdIn(spaceIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1], (a, b) -> a));
        Map<UUID, Long> grainCounts = grainRepository.countBySpaceIdIn(spaceIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1], (a, b) -> a));
        Map<UUID, String> skillStatuses = skillRepository.findBySpaceIdIn(spaceIds).stream()
                .collect(Collectors.toMap(
                        com.aiextract.model.Skill::getSpaceId,
                        com.aiextract.model.Skill::getStatus, (a, b) -> a));

        // 批量用户名称
        List<com.aiextract.model.User> owners = userRepository.findAllById(spaceUserIds);
        Map<UUID, String> userNames = owners.stream()
                .collect(Collectors.toMap(
                        com.aiextract.model.User::getId,
                        com.aiextract.model.User::getName, (a, b) -> a));

        List<Map<String, Object>> content = new ArrayList<>();
        for (Space s : spaces) {
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
    public ApiResponse<Map<String, Object>> getSpace(@PathVariable String spaceId,
            @RequestParam(defaultValue = "1") int reportPage,
            @RequestParam(defaultValue = "20") int reportSize,
            @RequestParam(defaultValue = "createdAt") String reportSort) {
        UUID spId = UUID.fromString(spaceId);
        Space space = spaceRepository.findById(spId)
                .orElseThrow(() -> new BusinessException(404, ErrorMessages.SPACE_NOT_FOUND));
        // super_admin: 无限制
        if (isSuperAdmin()) {
            return ApiResponse.success(spaceService.getSpaceDetail(spaceId, reportPage, reportSize, reportSort));
        }
        // company_admin: 只能看本公司
        if (isAdmin()) {
            UUID companyId = TokenContext.getCompanyId();
            if (companyId != null) {
                Set<UUID> companySpaceIds = companyScopeService.getSpaceIds(companyId);
                if (companySpaceIds != null && !companySpaceIds.contains(spId)) {
                    throw new BusinessException(403, "无权访问其他企业的空间");
                }
            }
            return ApiResponse.success(spaceService.getSpaceDetail(spaceId, reportPage, reportSize, reportSort));
        }
        // employee: 只能看自己的
        if (!getCurrentUserId().equals(space.getUserId())) {
            throw new BusinessException(403, "无权访问他人的空间");
        }
        return ApiResponse.success(spaceService.getSpaceDetail(spaceId, reportPage, reportSize, reportSort));
    }

    private boolean isSuperAdmin() {
        return SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("super_admin"));
    }

    private boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication()
                .getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("super_admin")
                        || a.getAuthority().equals("company_admin"));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> createSpace(@RequestBody Map<String, Object> body) {
        // 仅 super_admin 或本人可创建空间，company_admin 无权
        if (isAdmin() && !isSuperAdmin()) {
            throw new BusinessException(403, "管理员无权创建空间，空间由用户上传素材或发起访谈时自动创建");
        }
        LocalDateTime now = LocalDateTime.now();
        UUID ownerId = isSuperAdmin()
                ? UUID.fromString((String) body.getOrDefault("userId", getCurrentUserId().toString()))
                : getCurrentUserId();
        Space s = Space.builder().id(UUID.randomUUID())
                .userId(ownerId)
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
        // 仅 super_admin 或本人可修改，company_admin 无权
        if (!isSuperAdmin() && !getCurrentUserId().equals(s.getUserId())) {
            throw new BusinessException(403, "无权修改他人的空间");
        }
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

}
