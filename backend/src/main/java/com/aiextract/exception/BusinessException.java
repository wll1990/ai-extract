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
     * 构造业务异常
     *
     * @param errorCode    HTTP状态码
     * @param errorMessage 错误描述
     */
    public BusinessException(int errorCode, String errorMessage) {
        super(errorMessage);
        this.errorCode = errorCode;
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
    }
}
