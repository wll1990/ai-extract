package com.aiextract.config;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * 当前登录用户门面 —— 统一从会话上下文读取，禁止再从 token 解析。
 *
 * <p>userId/companyId 来自过滤器写入的 {@link TokenContext}；role 从 SecurityContext
 * 的 authority 中识别（角色名与权限码同源 {@link RolePermissions}）。</p>
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        return TokenContext.get();
    }

    public static UUID companyId() {
        return TokenContext.getCompanyId();
    }

    public static UUID homeCompanyId() {
        return TokenContext.getHomeCompanyId();
    }

    public static String role() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(RolePermissions::isRole)
                .findFirst()
                .orElse(null);
    }
}
