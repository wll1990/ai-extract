package com.aiextract;

import com.aiextract.common.AiErrorMapper;
import com.aiextract.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型错误码映射回归 —— 用户不再只看到「AI服务暂时不可用」。
 */
class AiErrorMapperTest {

    @Test
    void unauthorized_mapsToKeyInvalid_andIsNotRetryable() {
        AiErrorMapper.AiError e = AiErrorMapper.map(new RuntimeException("401 - {\"error\":\"invalid api key\"}"));

        assertEquals(AiErrorMapper.KEY_INVALID, e.code());
        assertTrue(e.message().contains("Key"));
        assertFalse(e.retryable());
    }

    @Test
    void insufficientBalance_mapsToQuotaExceeded() {
        AiErrorMapper.AiError e = AiErrorMapper.map(new RuntimeException("402 - {\"error\":\"Insufficient Balance\"}"));

        assertEquals(AiErrorMapper.QUOTA_EXCEEDED, e.code());
        assertTrue(e.message().contains("余额"));
    }

    @Test
    void rateLimited_isRetryable() {
        AiErrorMapper.AiError e = AiErrorMapper.map(new RuntimeException("429 Too Many Requests"));

        assertEquals(AiErrorMapper.RATE_LIMITED, e.code());
        assertTrue(e.retryable());
    }

    @Test
    void upstream5xx_isRetryable() {
        AiErrorMapper.AiError e = AiErrorMapper.map(new RuntimeException("503 Service Unavailable"));

        assertEquals(AiErrorMapper.UPSTREAM_ERROR, e.code());
        assertTrue(e.retryable());
    }

    @Test
    void timeout_wrappedInCause_mapsToTimeout() {
        RuntimeException root = new RuntimeException("Read timed out");
        AiErrorMapper.AiError e = AiErrorMapper.map(new RuntimeException("AI call failed", root));

        assertEquals(AiErrorMapper.TIMEOUT, e.code());
        assertTrue(e.retryable());
    }

    @Test
    void businessSubCodeWins() {
        BusinessException be = new BusinessException(409, "AI_KEY_DISABLED", "企业 AI Key 已停用，请联系管理员");

        AiErrorMapper.AiError e = AiErrorMapper.map(be);

        assertEquals("AI_KEY_DISABLED", e.code());
        assertEquals("企业 AI Key 已停用，请联系管理员", e.message());
        assertFalse(e.retryable());
    }

    @Test
    void describe_containsMessageAndCode() {
        String text = AiErrorMapper.describe(new RuntimeException("401 Unauthorized"));

        assertTrue(text.contains("Key"));
        assertTrue(text.contains(AiErrorMapper.KEY_INVALID));
    }

    @Test
    void unknownError_fallsBackToServiceUnavailable() {
        AiErrorMapper.AiError e = AiErrorMapper.map(new IllegalStateException("model returned blank"));

        assertEquals(AiErrorMapper.SERVICE_UNAVAILABLE, e.code());
    }
}
