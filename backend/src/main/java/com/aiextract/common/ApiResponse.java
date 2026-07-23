package com.aiextract.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 统一API响应封装
 *
 * <p>所有Controller接口必须使用此类封装返回值，确保前端收到统一格式的响应。</p>
 *
 * @param <T> 响应数据类型
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    /**
     * 状态码（200成功，4xx客户端错误，5xx服务端错误）
     */
    private int code;

    /**
     * 提示信息
     */
    private String message;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 构建成功响应（带数据）
     *
     * @param data 响应数据
     * @param <T>  数据类型
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message("success")
                .data(data)
                .build();
    }

    /**
     * 构建成功响应（无数据）
     *
     * @param <T> 数据类型
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    /**
     * 构建错误响应
     *
     * @param code    错误码
     * @param message 错误信息
     * @param <T>     数据类型
     * @return ApiResponse
     */
    public static <T> ApiResponse<T> error(int code, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .data(null)
                .build();
    }

    public static <T> ApiResponse<T> error(int code, T data) {
        return ApiResponse.<T>builder()
                .code(code)
                .data(data)
                .message(null)
                .build();
    }
}
