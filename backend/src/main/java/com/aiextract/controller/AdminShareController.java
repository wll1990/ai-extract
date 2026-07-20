package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.SkillShare;
import com.aiextract.service.ShareService;
import com.aiextract.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 管理端分享管理接口 — /admin 前缀由 SecurityConfig 限定 SUPER_ADMIN
 *
 * @author AI Extract Team
 * @since 2026-07-19
 */
@Slf4j
@RestController
@RequestMapping("/admin/skills/{skillId}/share")
@RequiredArgsConstructor
public class AdminShareController {

    private final ShareService shareService;
    private final JwtUtil jwtUtil;

    private UUID extractUserId() {
        String token = (String) SecurityContextHolder.getContext().getAuthentication().getCredentials();
        return jwtUtil.getUserIdFromToken(token);
    }

    /**
     * 生成（或获取已有）分享链接
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> getOrCreate(@PathVariable UUID skillId) {
        return ApiResponse.success(toMap(shareService.getOrCreateShare(skillId, extractUserId())));
    }

    /**
     * 查询分享（未生成时 404，前端据此显示"生成"按钮）
     */
    @GetMapping
    public ApiResponse<Map<String, Object>> get(@PathVariable UUID skillId) {
        SkillShare share = shareService.findShare(skillId)
                .orElseThrow(() -> new BusinessException(404, "尚未生成分享链接"));
        return ApiResponse.success(toMap(share));
    }

    /**
     * 共享开关
     */
    @PutMapping
    public ApiResponse<Map<String, Object>> toggle(
            @PathVariable UUID skillId, @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return ApiResponse.success(toMap(shareService.toggleShare(skillId, enabled)));
    }

    private Map<String, Object> toMap(SkillShare share) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skillId", share.getSkillId().toString());
        m.put("shareCode", share.getShareCode());
        m.put("channel", share.getChannel());
        m.put("enabled", share.getEnabled());
        m.put("createdAt", share.getCreatedAt() != null ? share.getCreatedAt().toString() : null);
        return m;
    }
}
