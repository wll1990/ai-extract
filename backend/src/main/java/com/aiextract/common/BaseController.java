package com.aiextract.common;

import com.aiextract.config.CurrentUser;
import com.aiextract.model.SkillShare;
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

    /** 从 SecurityContext 提取当前请求的原始 token 字符串 */
    protected String getToken() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getCredentials();
    }

    /** 当前登录用户 ID —— 从会话上下文读取，不解析 token */
    protected UUID extractUserId() {
        return CurrentUser.id();
    }

    /** 当前角色 —— 从 SecurityContext 的 authority 中识别角色名 */
    protected String extractRole() {
        return CurrentUser.role();
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
