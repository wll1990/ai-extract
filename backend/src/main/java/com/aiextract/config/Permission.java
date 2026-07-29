package com.aiextract.config;

/**
 * 权限码常量定义。
 *
 * <p>Spring Security 使用这些常量作为 authority 进行 {@code hasAuthority()} 校验。
 * 前端通过 /auth/me 的 permissions 字段获取用户拥有的权限码列表。</p>
 *
 * <h3>权限分级</h3>
 * <ul>
 *   <li><b>Platform 级</b> — 仅 super_admin 拥有，管理所有企业和平台配置</li>
 *   <li><b>Company 级</b> — super_admin + company_admin 拥有，管理本企业数据</li>
 *   <li><b>Basic 级</b> — 所有 B 端用户拥有，使用分身和个人空间</li>
 * </ul>
 *
 * @author AI Extract Team
 * @since 2026-07-28
 */
public final class Permission {

    private Permission() {}

    // ═══════════════════════════════════════════════════════════
    // Platform 级（仅 super_admin 拥有）
    // ═══════════════════════════════════════════════════════════

    /** 企业管理 + 注册码生成 */
    public static final String COMPANY_MANAGE = "COMPANY_MANAGE";

    /** 合作方 + API 密钥管理 */
    public static final String PARTNER_MANAGE = "PARTNER_MANAGE";

    /** IM 渠道配置（飞书/钉钉/企微） */
    public static final String IM_MANAGE = "IM_MANAGE";

    /** 全局 Token 消耗统计 */
    public static final String TOKEN_VIEW_ALL = "TOKEN_VIEW_ALL";

    // ═══════════════════════════════════════════════════════════
    // Company 级（super_admin + company_admin 拥有）
    // ═══════════════════════════════════════════════════════════

    /** 工作台 + 数据看板 */
    public static final String DASHBOARD_VIEW = "DASHBOARD_VIEW";

    /** 本企业用户管理 */
    public static final String USER_MANAGE = "USER_MANAGE";

    /** 分身审核/上下架 */
    public static final String SKILL_MANAGE = "SKILL_MANAGE";

    /** 分身调优 + 数据洞察 */
    public static final String SKILL_TUNING = "SKILL_TUNING";

    /** 素材管理 */
    public static final String MATERIAL_MANAGE = "MATERIAL_MANAGE";

    /** 对话历史查看 */
    public static final String CONVERSATION_VIEW = "CONVERSATION_VIEW";

    /** 场景覆盖分析 */
    public static final String SCENE_COVERAGE = "SCENE_COVERAGE";

    /** 萃取师经验库管理 */
    public static final String EXPERT_MANAGE = "EXPERT_MANAGE";

    /** 颗粒管理（CRUD/废弃/恢复） */
    public static final String GRAIN_MANAGE = "GRAIN_MANAGE";

    /** 本企业 Token 消耗统计 */
    public static final String TOKEN_VIEW_COMPANY = "TOKEN_VIEW_COMPANY";

    /** 组织/综合分身管理 */
    public static final String ORG_SKILL_MANAGE = "ORG_SKILL_MANAGE";

    // ═══════════════════════════════════════════════════════════
    // Basic 级（所有 B 端用户拥有）
    // ═══════════════════════════════════════════════════════════

    /** 使用分身（QA/Talk/Practice） */
    public static final String SKILL_USE = "SKILL_USE";

    /** 个人空间 */
    public static final String SPACE_OWN = "SPACE_OWN";
}
