package com.aiextract.config;

import java.util.Map;
import java.util.Set;

/**
 * 角色→权限码映射表 — 单一真相源。
 *
 * <p>三个派生消费点从同一份映射表读取，保证一致性：
 * <ol>
 *   <li>{@link com.aiextract.config.SessionAuthFilter} — 登录时查映射表，设为 Spring Security GrantedAuthority</li>
 *   <li>{@link com.aiextract.service.AuthService#getCurrentUser} — /auth/me 返回 permissions 字段</li>
 *   <li>业务代码 — 通过 {@link #hasPermission} 替代硬编码字符串角色比较</li>
 * </ol></p>
 *
 * <h3>新增角色</h3>
 * 只需在 {@link #ROLE_MAP} 加一行映射。SecurityConfig、SessionAuthFilter、/auth/me、前端导航
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
    public static final String C_GUEST = "c_guest";
    public static final String C_USER = "c_user";
    public static final String C_PARTNER = "c_partner";

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
            Permission.GRAIN_MANAGE, Permission.KNOWLEDGE_MANAGE, Permission.KNOWLEDGE_SUBMIT,
            Permission.TOKEN_VIEW_COMPANY,
            Permission.EVAL_MANAGE, Permission.EVAL_REVIEW,
            Permission.TRAINING_MANAGE, Permission.TRAINING_REVIEW, Permission.TRAINING_LEARN,
            Permission.ORG_SKILL_MANAGE, Permission.AI_MODEL_MANAGE,
            Permission.SALES_REVIEW, Permission.SALES_REVIEW_VIEW_ALL,
            Permission.SALES_LEAD_MANAGE,
            // C 端服务等级
            Permission.INTERVIEW_USE,
            // Basic
            Permission.SKILL_USE, Permission.SPACE_OWN
        ),
        COMPANY_ADMIN, Set.of(
            // Company
            Permission.DASHBOARD_VIEW, Permission.USER_MANAGE,
            Permission.SKILL_MANAGE, Permission.SKILL_TUNING,
            Permission.MATERIAL_MANAGE, Permission.CONVERSATION_VIEW,
            Permission.SCENE_COVERAGE, Permission.EXPERT_MANAGE,
            Permission.GRAIN_MANAGE, Permission.KNOWLEDGE_MANAGE, Permission.KNOWLEDGE_SUBMIT,
            Permission.TOKEN_VIEW_COMPANY,
            Permission.EVAL_MANAGE, Permission.EVAL_REVIEW,
            Permission.TRAINING_MANAGE, Permission.TRAINING_REVIEW, Permission.TRAINING_LEARN,
            Permission.ORG_SKILL_MANAGE, Permission.AI_MODEL_MANAGE,
            Permission.IM_MANAGE,
            Permission.SALES_REVIEW, Permission.SALES_REVIEW_VIEW_ALL,
            Permission.SALES_LEAD_MANAGE,
            // C 端服务等级
            Permission.INTERVIEW_USE,
            // Basic
            Permission.SKILL_USE, Permission.SPACE_OWN
        ),
        EMPLOYEE, Set.of(
            Permission.SKILL_USE, Permission.SPACE_OWN,
            Permission.MATERIAL_MANAGE, Permission.INTERVIEW_USE,
            // 一线销售可提交知识/案例草稿，审批仍由管理员完成
            Permission.KNOWLEDGE_SUBMIT,
            // 员工既可能是学员，也可能被指派为批次导师（数据级范围由 Service 再判定）
            Permission.TRAINING_REVIEW, Permission.TRAINING_LEARN,
            // 销售复盘：所有 B 端员工都能发起/看自己的；全公司范围要 VIEW_ALL
            Permission.SALES_REVIEW, Permission.SALES_LEAD_MANAGE
        ),
        // C-end 角色 — 服务等级，游客仅基础使用，注册/合作方增加访谈
        C_GUEST, Set.of(
            Permission.SKILL_USE
        ),
        C_USER, Set.of(
            Permission.SKILL_USE, Permission.INTERVIEW_USE
        ),
        C_PARTNER, Set.of(
            Permission.SKILL_USE, Permission.INTERVIEW_USE
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
    /** 判断一个 authority 是否是角色名（区别于权限码），供 Controller 从 SecurityContext 反推 role。 */
    public static boolean isRole(String value) {
        return value != null && ROLE_MAP.containsKey(value);
    }

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
