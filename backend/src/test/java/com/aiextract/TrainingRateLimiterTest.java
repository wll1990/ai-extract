package com.aiextract;

import com.aiextract.service.TrainingRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 陪练发言额度：原子扣减 + Redis 异常回退 DB 计数。
 */
@ExtendWith(MockitoExtension.class)
class TrainingRateLimiterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private org.springframework.data.redis.core.ValueOperations<String, String> valueOps;

    private TrainingRateLimiter limiter;
    private final UUID attemptId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        limiter = new TrainingRateLimiter(redisTemplate);
        org.springframework.test.util.ReflectionTestUtils.setField(limiter, "maxHintsPerAttempt", 10);
    }

    @Test
    void allowedWhenScriptReturnsOne() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

        assertTrue(limiter.tryConsumePracticeMessage(attemptId, 0, 40));
    }

    @Test
    void rejectedWhenScriptReturnsZero() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);

        assertFalse(limiter.tryConsumePracticeMessage(attemptId, 40, 40));
    }

    @Test
    void redisFailure_fallsBackToPersistedCount() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertTrue(limiter.tryConsumePracticeMessage(attemptId, 10, 40));
        assertFalse(limiter.tryConsumePracticeMessage(attemptId, 40, 40));
    }

    @Test
    void hintStillUsesIncrementWhenRedisHealthy() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        assertTrue(limiter.allowHint(attemptId));
    }
}
