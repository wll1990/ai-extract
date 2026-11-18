package com.aiextract.exception;

import com.aiextract.common.AiErrorMapper;
import com.aiextract.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    /**
     * SSE 端点的请求头是 {@code Accept: text/event-stream}，同步抛出的业务异常
     * 如果走普通 ResponseEntity，会被内容协商判成 406，客户端最终只看到一条
     * 无关的 401。这里在客户端不接受 JSON 时直接写响应体，绕开协商。
     *
     * @return true 表示已直接写回响应，调用方应返回 null
     */
    private boolean writeJsonIfNotAcceptable(HttpServletRequest request, HttpServletResponse response,
                                            int status, ApiResponse<?> body) {
        String accept = request.getHeader("Accept");
        boolean acceptsJson = accept == null || accept.isBlank()
                || accept.contains("*/*") || accept.contains("application/json");
        if (acceptsJson) {
            return false;
        }
        try {
            response.setStatus(status);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(body));
        } catch (Exception writeError) {
            log.warn("SSE 错误响应写回失败: {}", writeError.getMessage());
        }
        return true;
    }

    /**
     * 处理业务异常
     *
     * @param e 业务异常
     * @return 错误响应
     */
    @ExceptionHandler(PartnerException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handlePartnerException(PartnerException e) {
        log.warn("合作方对接异常, errorCode: {}, message: {}", e.getPartnerErrorCode(), e.getMessage());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("errorCode", e.getPartnerErrorCode());
        data.put("message", e.getMessage());
        return ResponseEntity.status(e.getErrorCode()).body(ApiResponse.error(e.getErrorCode(), data));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e,
            HttpServletRequest request, HttpServletResponse response) {
        log.warn("业务异常, code: {}, subCode: {}, message: {}", e.getErrorCode(), e.getSubCode(), e.getMessage());
        ApiResponse<Void> body = ApiResponse.error(e.getErrorCode(), e.getSubCode(), e.getMessage());
        if (writeJsonIfNotAcceptable(request, response, e.getErrorCode(), body)) {
            return null;
        }
        return ResponseEntity.status(e.getErrorCode()).body(body);
    }

    /**
     * 处理参数校验异常
     *
     * @param e 参数校验异常
     * @return 错误响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e,
            HttpServletRequest request, HttpServletResponse response) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("参数校验失败: {}", message);
        ApiResponse<Void> body = ApiResponse.error(HttpStatus.BAD_REQUEST.value(), message);
        if (writeJsonIfNotAcceptable(request, response, HttpStatus.BAD_REQUEST.value(), body)) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
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
     * 处理请求体不可读（JSON 语法错误、字段类型与 DTO 不匹配）
     *
     * <p>这一组都是「客户端传错」而不是「服务端故障」，落到 Exception 兜底会返回 500，
     * 前端和监控都会误判成服务挂了。</p>
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(HttpStatus.BAD_REQUEST.value(),
                "请求体格式错误，请检查 JSON 结构与字段类型");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 处理路径/查询参数类型不匹配（如 UUID、页码传了非法值）
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误: name={}, value={}", e.getName(), e.getValue());
        ApiResponse<Void> response = ApiResponse.error(HttpStatus.BAD_REQUEST.value(),
                "参数类型错误: " + e.getName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 处理缺失必填请求参数
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        log.warn("缺少必填参数: {}", e.getParameterName());
        ApiResponse<Void> response = ApiResponse.error(HttpStatus.BAD_REQUEST.value(),
                "缺少必填参数: " + e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * 处理 HTTP 方法不支持（如接口只接受 POST，客户端用了 GET）
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(HttpStatus.METHOD_NOT_ALLOWED.value(),
                "请求方法不支持: " + e.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(response);
    }

    /**
     * 处理请求内容类型不支持（如接口只接受 application/json）
     */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(
            org.springframework.web.HttpMediaTypeNotSupportedException e) {
        log.warn("媒体类型不支持: {}", e.getMessage());
        ApiResponse<Void> response = ApiResponse.error(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                "请求内容类型不支持，请使用 application/json");
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(response);
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
     * 主库/会话存储不可用 → 503。
     *
     * <p>前端对 503 的语义是「服务暂不可用，保留本地登录态」；不能落到 500，
     * 否则会被当成未知错误、用户被反复弹登录。</p>
     */
    @ExceptionHandler({
            com.aiextract.service.session.SessionUnavailableException.class,
            org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.jdbc.CannotGetJdbcConnectionException.class,
            org.springframework.transaction.CannotCreateTransactionException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleStorageUnavailable(Exception e) {
        log.error("存储不可用: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(503, "服务暂不可用，请稍后重试"));
    }

    /**
     * 处理文件上传大小超限
     */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleMaxUploadSize(
            org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        log.warn("文件大小超限: {}", e.getMessage());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("maxSize", "50MB");
        data.put("suggestion", "文件过大，建议拆分为多个小文件分批上传，处理速度更快、萃取质量更高");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.error(413, data));
    }

    /**
     * 处理路径不存在（无匹配 Controller / 无静态资源）
     *
     * <p>Spring 6.1 起未匹配路径抛 NoResourceFoundException，若不单独处理会被下方的
     * Exception 兜底映射成 500，前端会把「调错接口」误判成「服务端挂了」。</p>
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.warn("请求路径不存在: {} /{}", e.getHttpMethod(), e.getResourcePath());
        ApiResponse<Void> response = ApiResponse.error(HttpStatus.NOT_FOUND.value(),
                "接口不存在: /" + e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    /**
     * 模型供应商异常（401/402/403/429/5xx/超时）。
     *
     * <p>同步 AI 接口（AI 整理、AI 建议等）如果走全局兜底，用户只会看到
     * 「服务器内部错误」；这里统一映射成可读原因 + 稳定 subCode，
     * 与 SSE / 后台任务使用同一套 {@link AiErrorMapper}。</p>
     */
    @ExceptionHandler({
            org.springframework.web.client.RestClientResponseException.class,
            org.springframework.web.reactive.function.client.WebClientResponseException.class,
            com.openai.errors.OpenAIException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleAiException(Exception e) {
        AiErrorMapper.AiError aiError = AiErrorMapper.map(e);
        log.warn("模型调用异常, code: {}, message: {}", aiError.code(), e.getMessage());
        int status = AiErrorMapper.RATE_LIMITED.equals(aiError.code())
                ? HttpStatus.TOO_MANY_REQUESTS.value()
                : HttpStatus.BAD_GATEWAY.value();
        ApiResponse<Void> response = ApiResponse.error(status, aiError.code(), aiError.message());
        return ResponseEntity.status(status).body(response);
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
