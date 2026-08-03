package com.aiextract.common;

import com.aiextract.model.SkillShare;
import com.aiextract.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Controller 公共基类 — 提供 JWT 提取、分享 Map 组装等跨 Controller 复用方法。
 *
 * @author AI Extract Team
 * @since 2026-07-31
 */
public abstract class BaseController {

    @Autowired
    protected JwtUtil jwtUtil;

    /** 从 SecurityContext 提取当前请求的 JWT token 字符串 */
    protected String getToken() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getCredentials();
    }

    /** 从 JWT 解出 userId */
    protected UUID extractUserId() {
        return jwtUtil.getUserIdFromToken(getToken());
    }

    /** 从 JWT 解出 role（B 端 super_admin/employee，C 端 c_guest/c_user） */
    protected String extractRole() {
        return jwtUtil.getRoleFromToken(getToken());
    }

    /** SkillShare → API 响应 Map（所有分享端点统一格式） */
    protected Map<String, Object> toShareMap(SkillShare share) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("skillId", share.getSkillId() != null ? share.getSkillId().toString() : null);
        map.put("shareCode", share.getShareCode());
        map.put("channel", share.getChannel());
        map.put("enabled", share.getEnabled());
        map.put("createdAt", share.getCreatedAt() != null ? share.getCreatedAt().toString() : null);
        return map;
    }
}
