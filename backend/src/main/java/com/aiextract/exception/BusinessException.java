package com.aiextract.exception;

import lombok.Getter;

/**
 * 业务异常类
 *
 * <p>用于表示业务逻辑层面的异常，如用户不存在、密码错误、权限不足等。
 * 通过 errorCode 和 errorMessage 向GlobalExceptionHandler传递异常信息。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * HTTP状态码
     */
    private final int errorCode;

    /**
     * 稳定的业务子码（可空）：同一种 HTTP 状态下的可区分原因，供前端做分支处理。
     */
    private final String subCode;

    /**
     * 构造业务异常
     *
     * @param errorCode    HTTP状态码
     * @param errorMessage 错误描述
     */
    public BusinessException(int errorCode, String errorMessage) {
        super(errorMessage);
        this.errorCode = errorCode;
        this.subCode = null;
    }

    /**
     * 构造带稳定子码的业务异常。
     *
     * @param errorCode    HTTP状态码
     * @param subCode      稳定子码（如 CERT_STATE_CHANGED）
     * @param errorMessage 错误描述
     */
    public BusinessException(int errorCode, String subCode, String errorMessage) {
        super(errorMessage);
        this.errorCode = errorCode;
        this.subCode = subCode;
    }

    /** 409 冲突 + 稳定子码的快捷构造 */
    public static BusinessException conflict(String subCode, String message) {
        return new BusinessException(409, subCode, message);
    }

    /**
     * 构造业务异常（含原始异常）
     *
     * @param errorCode    HTTP状态码
     * @param errorMessage 错误描述
     * @param cause        原始异常
     */
    public BusinessException(int errorCode, String errorMessage, Throwable cause) {
        super(errorMessage, cause);
        this.errorCode = errorCode;
        this.subCode = null;
    }
}
