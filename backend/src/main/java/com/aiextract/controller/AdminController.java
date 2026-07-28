package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.model.*;
import com.aiextract.repository.*;
import com.aiextract.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import com.aiextract.common.ErrorMessages;

/**
 * 管理后台控制器 — 补齐缺失的 /admin/* 端点
 *
 * @author AI Extract Team
 * @since 2026-06-30
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
    private static final String KEY_BRAND_COLOR = "brandColor";
    private static final String KEY_COMPANY_NAME = "companyName";
    private static final String KEY_LOGO_URL = "logoUrl";


    private final SpaceRepository spaceRepository;
    private final ExperienceGrainRepository grainRepository;
    private final ReportRepository reportRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final com.aiextract.service.AdminService adminService;
    private final JwtUtil jwtUtil;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    /** 从 SecurityContext 取 token（JwtAuthFilter 已从 Cookie/Header 提取并设置） */
    private String getToken() {
        return (String) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getCredentials();
    }

    private UUID extractCompanyId() {
        return jwtUtil.getCompanyIdFromToken(getToken());
    }

    // ==================== /admin/dashboard ====================

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        return ApiResponse.success(adminService.getDashboard());
    }

    // ==================== /admin/spaces ====================

    @GetMapping("/spaces")
    public ApiResponse<Map<String, Object>> getSpaces(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {

        UUID companyId = extractCompanyId();
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

        // 批量查询：避免循环内 N+1
        List<UUID> spaceIds = spaces.stream().map(Space::getId).toList();
        List<UUID> userIds = spaces.stream().map(Space::getUserId).distinct().toList();

        Map<UUID, String> userNames = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getName, (a, b) -> a));
        Map<UUID, Long> reportCounts = reportRepository.countBySpaceIdIn(spaceIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1], (a, b) -> a));

        List<Map<String, Object>> content = new ArrayList<>();
        for (Space s : spaces) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId().toString());
            item.put("ownerName", userNames.getOrDefault(s.getUserId(), "未知用户"));
            item.put("title", s.getTitle());
            item.put("reportCount", reportCounts.getOrDefault(s.getId(), 0L).intValue());
            item.put("status", s.getStatus());
            item.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            content.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        result.put("page", page);
        result.put("size", size);
        result.put("total", sp.getTotalElements());
        result.put("totalPages", sp.getTotalPages());
        return ApiResponse.success(result);
    }

    // ==================== /admin/scene-coverage ====================

    @GetMapping("/scene-coverage")
    public ApiResponse<Map<String, Object>> getSceneCoverage() {
        return ApiResponse.success(adminService.getSceneCoverage());
    }

    // ==================== /admin/config ====================

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig() {
        UUID companyId = extractCompanyId();
        Company company = companyRepository.findById(companyId).orElse(null);
        Map<String, Object> config = new LinkedHashMap<>();
        if (company != null) {
            config.put(KEY_LOGO_URL, company.getLogoUrl());
            config.put(KEY_BRAND_COLOR, company.getBrandColor());
            config.put(KEY_COMPANY_NAME, company.getName());
        }
        return ApiResponse.success(config);
    }

    @PutMapping("/config")
    public ApiResponse<Map<String, Object>> updateConfig(
            @RequestBody Map<String, Object> body) {
        UUID companyId = extractCompanyId();
        Company company = companyRepository.findById(companyId).orElse(null);
        if (company == null) {
            return ApiResponse.error(404, ErrorMessages.COMPANY_NOT_FOUND);
        }
        if (body.containsKey(KEY_LOGO_URL)) { company.setLogoUrl((String) body.get(KEY_LOGO_URL)); }
        if (body.containsKey(KEY_BRAND_COLOR)) { company.setBrandColor((String) body.get(KEY_BRAND_COLOR)); }
        if (body.containsKey(KEY_COMPANY_NAME)) { company.setName((String) body.get(KEY_COMPANY_NAME)); }
        // 模型配置和IM配置存扩展字段（简化处理）
        companyRepository.save(company);
        log.info("企业配置已更新, companyId: {}", companyId);
        return ApiResponse.success(Map.of("message", "配置已更新"));
    }

    private final com.aiextract.service.InterviewService interviewService;

    // ==================== /admin/invite ====================

    /**
     * 生成访谈邀请码。不绑定 space，space 由扫码登录的员工自己决定。
     * 写入 interview_invite_code 表，UNIQUE 约束防重。
     */
    @PostMapping("/invite")
    public ApiResponse<Map<String, Object>> createInvite(
            @RequestBody Map<String, Object> body) {
        UUID companyId = extractCompanyId();
        // expireDays 不传或传 0 = 永久有效
        int expireDays = body.containsKey("expireDays") ? ((Number) body.get("expireDays")).intValue() : 0;
        String inviteCode = interviewService.generateInviteCode(companyId, expireDays,
            jwtUtil.getUserIdFromToken(getToken()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inviteCode", inviteCode);
        result.put("inviteUrl", frontendUrl + "/h5/interview/m/" + inviteCode);
        return ApiResponse.success(result);
    }

    // ==================== /admin/users ====================

    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> listUsers() {
        UUID companyId = extractCompanyId();
        List<User> users = userRepository.findByCompanyId(companyId);
        List<UUID> userIds = users.stream().map(User::getId).toList();

        // 批量查空间（1次查询替代 N 次 findByUserId）
        Map<UUID, UUID> userSpaceMap = spaceRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.toMap(Space::getUserId, Space::getId, (a, b) -> a));

        List<Map<String, Object>> list = users.stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId().toString());
            m.put("name", u.getName());
            m.put("account", u.getAccount());
            m.put("role", u.getRole());
            m.put("isActive", u.getIsActive());
            m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : null);
            UUID spaceId = userSpaceMap.get(u.getId());
            m.put("spaceId", spaceId != null ? spaceId.toString() : null);
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.success(list);
    }

}
