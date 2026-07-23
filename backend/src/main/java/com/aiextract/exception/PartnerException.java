package com.aiextract.exception;

/**
 * 合作方对接异常 — 统一错误码，合作方可编程处理。
 */
public class PartnerException extends BusinessException {

    private final String partnerErrorCode;

    public PartnerException(String partnerErrorCode, String message) {
        super(401, message);
        this.partnerErrorCode = partnerErrorCode;
    }

    public PartnerException(int httpStatus, String partnerErrorCode, String message) {
        super(httpStatus, message);
        this.partnerErrorCode = partnerErrorCode;
    }

    public String getPartnerErrorCode() {
        return partnerErrorCode;
    }

    // ── 预定义错误 ──

    public static PartnerException appNotFound() {
        return new PartnerException("PARTNER_APP_NOT_FOUND", "合作方不存在，请检查 appId");
    }

    public static PartnerException appDisabled() {
        return new PartnerException(403, "PARTNER_APP_DISABLED", "该服务暂时不可用，请联系客服");
    }

    public static PartnerException skMismatch() {
        return new PartnerException(401, "PARTNER_SK_MISMATCH", "身份验证失败，请联系客服");
    }

    public static PartnerException tokenExpired() {
        return new PartnerException(401, "PARTNER_TOKEN_EXPIRED", "登录已过期，请刷新页面重试");
    }

    public static PartnerException tokenInvalid(String detail) {
        return new PartnerException(401, "PARTNER_TOKEN_INVALID", "身份验证失败: " + detail);
    }

    public static PartnerException shareNotFound() {
        return new PartnerException(404, "SHARE_NOT_FOUND", "该链接已失效");
    }
}
