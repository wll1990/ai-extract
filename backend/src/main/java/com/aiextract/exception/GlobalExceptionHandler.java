package com.aiextract.exception;

import com.aiextract.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 全局异常处理器
 *
 * <p>统一处理Controller层抛出的各类异常，确保返回格式一致的错误响应。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     *
     * @param e 业务异常
     * @return 错误响应
     */
    @ExceptionHandler(PartnerException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handlePartnerException(PartnerException e) {
        log.warn("合作方对接异常, errorCode: {}, message: {}", e.getPartnerErrorCode(), e.getMessage());
        Map<String, String> data = new java.util.LinkedHashMap<>();
        data.put("errorCode", e.getPartnerErrorCode());
        data.put("message", e.getMessage());
        return ResponseEntity.status(e.getErrorCode()).body(ApiResponse.error(e.getErrorCode(), data));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        log.warn("业务异常, code: {}, message: {}", e.getErrorCode(), e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(e.getErrorCode(), e.getMessage());
        return ResponseEntity.status(e.getErrorCode()).body(response);
    }

    /**
     * 处理参数校验异常
     *
     * @param e 参数校验异常
     * @return 错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        ApiResponse<Void> response = ApiResponse.error(HttpStatus.BAD_REQUEST.value(), message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 处理 ResponseStatusException（Spring 内置异常 → 统一 JSON 格式）
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(org.springframework.web.server.ResponseStatusException e) {
        log.warn("请求异常, status: {}, reason: {}", e.getStatusCode(), e.getReason());
        ApiResponse<Void> response = ApiResponse.error(e.getStatusCode().value(),
                e.getReason() != null ? e.getReason() : "请求参数错误");
        return ResponseEntity.status(e.getStatusCode()).body(response);
    }

    /**
     * 处理非法参数（如 UUID 格式错误）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数格式错误: {}", e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(HttpStatus.BAD_REQUEST.value(),
                "参数格式错误: " + (e.getMessage() != null ? e.getMessage() : "请检查请求参数"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 处理数据库完整性约束违反异常（如外键约束、唯一约束）
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(
            org.springframework.dao.DataIntegrityViolationException e) {
        log.error("数据库约束违反: {}", e.getMostSpecificCause().getMessage(), e);
        ApiResponse<Void> response = ApiResponse.error(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "数据操作失败，可能存在关联数据不完整或重复");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * 处理其他未捕获异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("服务器内部异常", e);
        ApiResponse<Void> response = ApiResponse.error(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "服务器内部错误，请稍后重试");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
