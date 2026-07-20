package com.aiextract.common;

/**
 * 业务错误消息常量
 *
 * @since 2026-07-01
 * @author AI Extract Team
 */
public final class ErrorMessages {

    private ErrorMessages() {}

    /** 实体不存在 */
    public static final String REPORT_NOT_FOUND = "报告不存在";
    public static final String SESSION_NOT_FOUND = "会话不存在";
    public static final String SKILL_NOT_FOUND = "Skill不存在";
    public static final String EXPERT_NOT_FOUND = "萃取师不存在";
    public static final String USER_NOT_FOUND = "用户不存在";
    public static final String SPACE_NOT_FOUND = "空间不存在";
    public static final String COMPANY_NOT_FOUND = "企业不存在";
    public static final String CHANNEL_NOT_FOUND = "渠道不存在";
    public static final String DOCUMENT_NOT_FOUND = "文档不存在";
    public static final String TOOL_NOT_FOUND = "工具不存在";

    /** 认证/授权 */
    public static final String PASSWORD_WRONG = "密码错误";
    public static final String ACCOUNT_DISABLED = "账户已禁用，请联系管理员";
    public static final String ACCOUNT_EXISTS = "该账号在企业内已存在";

    /** 业务 */
    public static final String AI_SERVICE_UNAVAILABLE = "AI服务暂时不可用";

    /** 素材 */
    public static final String MATERIAL_NOT_BELONG_TO_SKILL = "素材不属于该分身";

    /** 降级/默认值 */
    public static final String DEFAULT_USER_NAME = "未知用户";
}
