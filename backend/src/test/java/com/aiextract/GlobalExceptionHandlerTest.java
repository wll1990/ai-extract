package com.aiextract;

import com.aiextract.common.ApiResponse;
import com.aiextract.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GlobalExceptionHandler 单元测试 —— 客户端传错的请求必须回 4xx，不能落到 Exception 兜底变 500。
 *
 * <p>背景：这些异常原先没有专属 Handler，被 {@code @ExceptionHandler(Exception.class)} 兜底成
 * 500「服务器内部错误」，前端会把「参数传错」误判成「服务端挂了」，监控也会误报。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void messageNotReadable_returns400() {
        HttpMessageNotReadableException e = new HttpMessageNotReadableException(
                "Cannot deserialize value of type `java.lang.String` from Array value",
                new MockHttpInputMessage(new byte[0]));

        ResponseEntity<ApiResponse<Void>> resp = handler.handleMessageNotReadable(e);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(400, resp.getBody().getCode());
        assertTrue(resp.getBody().getMessage().contains("请求体格式错误"));
    }

    @Test
    void typeMismatch_returns400() throws NoSuchMethodException {
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("sampleEndpoint", UUID.class);
        MethodParameter param = new MethodParameter(method, 0);
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
                "not-a-uuid", UUID.class, "id", param, new IllegalArgumentException("bad uuid"));

        ResponseEntity<ApiResponse<Void>> resp = handler.handleTypeMismatch(e);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(400, resp.getBody().getCode());
        assertTrue(resp.getBody().getMessage().contains("id"));
    }

    @Test
    void missingParameter_returns400() {
        MissingServletRequestParameterException e =
                new MissingServletRequestParameterException("datasetId", "String");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleMissingParameter(e);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertEquals(400, resp.getBody().getCode());
        assertTrue(resp.getBody().getMessage().contains("datasetId"));
    }

    @Test
    void methodNotSupported_returns405() {
        HttpRequestMethodNotSupportedException e = new HttpRequestMethodNotSupportedException("DELETE");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleMethodNotSupported(e);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, resp.getStatusCode());
        assertEquals(405, resp.getBody().getCode());
        assertTrue(resp.getBody().getMessage().contains("DELETE"));
    }

    @Test
    void mediaTypeNotSupported_returns415() {
        HttpMediaTypeNotSupportedException e = new HttpMediaTypeNotSupportedException("text/plain");

        ResponseEntity<ApiResponse<Void>> resp = handler.handleMediaTypeNotSupported(e);

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, resp.getStatusCode());
        assertEquals(415, resp.getBody().getCode());
        assertTrue(resp.getBody().getMessage().contains("application/json"));
    }

    /** MethodParameter 需要真实方法引用，仅为构造异常用，不参与业务。 */
    @SuppressWarnings("unused")
    private void sampleEndpoint(UUID id) {
    }
}
