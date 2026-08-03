package com.aiextract.config;

import java.util.Map;
import java.util.Set;

/**
 * 角色→权限码映射表 — 单一真相源。
 *
 * <p>三个派生消费点从同一份映射表读取，保证一致性：
 * <ol>
 *   <li>{@link com.aiextract.config.JwtAuthFilter} — 登录时查映射表，设为 Spring Security GrantedAuthority</li>
 *   <li>{@link com.aiextract.service.AuthService#getCurrentUser} — /auth/me 返回 permissions 字段</li>
 *   <li>业务代码 — 通过 {@link #hasPermission} 替代硬编码字符串角色比较</li>
 * </ol></p>
 *
 * <h3>新增角色</h3>
 * 只需在 {@link #ROLE_MAP} 加一行映射。SecurityConfig、JwtAuthFilter、/auth/me、前端导航
 * 全部自动生效，无需额外改动。
 *
 * <h3>修改已有角色权限</h3>
 * 只需在对应角色的 Set 中增删权限码。下次登录生效。
 *
 * @author AI Extract Team
 * @since 2026-07-28
 */
public final class RolePermissions {

    private RolePermissions() {}

    public static final String SUPER_ADMIN = "super_admin";
    public static final String COMPANY_ADMIN = "company_admin";
    public static final String EMPLOYEE = "employee";

    /**
     * 角色→权限码集合映射。
     *
     * <p>修改此映射表即可调整任意角色的权限，下次登录生效。
     * Platform 级权限码只有 super_admin 拥有，SecurityConfig 通过
     * URL 细粒度规则配合实现仅超管可访问。</p>
     */
    private static final Map<String, Set<String>> ROLE_MAP = Map.of(
        SUPER_ADMIN, Set.of(
            // Platform
            Permission.COMPANY_MANAGE, Permission.PARTNER_MANAGE,
            Permission.IM_MANAGE, Permission.TOKEN_VIEW_ALL,
            // Company
            Permission.DASHBOARD_VIEW, Permission.USER_MANAGE,
            Permission.SKILL_MANAGE, Permission.SKILL_TUNING,
            Permission.MATERIAL_MANAGE, Permission.CONVERSATION_VIEW,
            Permission.SCENE_COVERAGE, Permission.EXPERT_MANAGE,
            Permission.GRAIN_MANAGE, Permission.TOKEN_VIEW_COMPANY,
            Permission.ORG_SKILL_MANAGE,
            // Basic
            Permission.SKILL_USE, Permission.SPACE_OWN
        ),
        COMPANY_ADMIN, Set.of(
            // Company
            Permission.DASHBOARD_VIEW, Permission.USER_MANAGE,
            Permission.SKILL_MANAGE, Permission.SKILL_TUNING,
            Permission.MATERIAL_MANAGE, Permission.CONVERSATION_VIEW,
            Permission.SCENE_COVERAGE, Permission.EXPERT_MANAGE,
            Permission.GRAIN_MANAGE, Permission.TOKEN_VIEW_COMPANY,
            Permission.ORG_SKILL_MANAGE,
            // Basic
            Permission.SKILL_USE, Permission.SPACE_OWN
        ),
        EMPLOYEE, Set.of(
            Permission.SKILL_USE, Permission.SPACE_OWN, Permission.MATERIAL_MANAGE
        ),
        // C-end 角色 — 服务等级，仅分配基础使用权限
        "c_guest", Set.of(
            Permission.SKILL_USE
        ),
        "c_user", Set.of(
            Permission.SKILL_USE
        ),
        "c_partner", Set.of(
            Permission.SKILL_USE
        )
    );

    /** 允许通过注册码或管理后台注册的角色（不允许注册为 super_admin） */
    public static final Set<String> REGISTRABLE_ROLES = Set.of(COMPANY_ADMIN, EMPLOYEE);

    /**
     * 查询角色拥有的权限码集合。
     *
     * @param role 角色字符串（如 "super_admin"、"company_admin"、"employee"）
     * @return 权限码集合，未知角色返回空集合
     */
    public static Set<String> getPermissions(String role) {
        return ROLE_MAP.getOrDefault(role, Set.of());
    }

    /**
     * 检查角色是否拥有某个权限码。
     *
     * @param role       角色字符串
     * @param permission 权限码（如 {@link Permission#USER_MANAGE}）
     * @return true 表示拥有该权限
     */
    public static boolean hasPermission(String role, String permission) {
        return getPermissions(role).contains(permission);
    }
}
