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

    /** 知识库 + 案例库管理（CRUD/审批/复制/上传萃取） */
    public static final String KNOWLEDGE_MANAGE = "KNOWLEDGE_MANAGE";
    /** 员工提交知识/案例草稿（只能 pending，审批仍需 KNOWLEDGE_MANAGE） */
    public static final String KNOWLEDGE_SUBMIT = "KNOWLEDGE_SUBMIT";

    /** 评测集管理（CRUD/提交审核/发布/版本/复制/AI 生成候选） */
    public static final String EVAL_MANAGE = "EVAL_MANAGE";

    /** 评测审核（通过/退回） */
    public static final String EVAL_REVIEW = "EVAL_REVIEW";

    /** 培训计划/批次/分配管理 */
    public static final String TRAINING_MANAGE = "TRAINING_MANAGE";

    /**
     * 培训复核与认证。
     *
     * <p>该权限也授予 employee：URL 级只回答「是不是复核人」，
     * 「能不能复核这个批次」由 TrainingAccessService 按 cohort.mentor_id 判定。</p>
     */
    public static final String TRAINING_REVIEW = "TRAINING_REVIEW";

    /** 培训学员端（我的培训/任务/考试） */
    public static final String TRAINING_LEARN = "TRAINING_LEARN";

    /** 本企业 Token 消耗统计 */
    public static final String TOKEN_VIEW_COMPANY = "TOKEN_VIEW_COMPANY";

    /** 本企业 AI Key 配置（DeepSeek Key / 回退开关） */
    public static final String AI_MODEL_MANAGE = "AI_MODEL_MANAGE";

    /** 销售复盘（发起/查看自己的/教练补充） */
    public static final String SALES_REVIEW = "SALES_REVIEW";

    /** 查看全公司销售复盘（company_admin / super_admin） */
    public static final String SALES_REVIEW_VIEW_ALL = "SALES_REVIEW_VIEW_ALL";

    /** 线索 / 跟进任务管理 */
    public static final String SALES_LEAD_MANAGE = "SALES_LEAD_MANAGE";

    /** 组织/综合分身管理 */
    public static final String ORG_SKILL_MANAGE = "ORG_SKILL_MANAGE";

    // ═══════════════════════════════════════════════════════════
    // C 端服务等级（区分游客/注册用户，替代「排除游客」的角色名判断）
    // ═══════════════════════════════════════════════════════════

    /** 访谈（非游客身份：B端全员 + c_user + c_partner） */
    public static final String INTERVIEW_USE = "INTERVIEW_USE";

    // ═══════════════════════════════════════════════════════════
    // Basic 级（所有 B 端用户拥有）
    // ═══════════════════════════════════════════════════════════

    /** 使用分身（QA/Talk/Practice） */
    public static final String SKILL_USE = "SKILL_USE";

    /** 个人空间 */
    public static final String SPACE_OWN = "SPACE_OWN";
}
