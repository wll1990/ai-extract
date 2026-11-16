package com.aiextract;

import com.aiextract.config.TokenRevocationService;
import com.aiextract.model.User;
import com.aiextract.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TokenRevocationService 单元测试 —— 版本号、双黑名单、Redis 故障回源 DB。
 *
 * <p>重点覆盖 S-F7：Redis 异常时不再 fail-open 放行一切，而是回源 DB 比对版本；
 * 只有 DB 也不可用才拒绝。</p>
 */
@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private UserRepository userRepository;

    private TokenRevocationService service;

    private final UUID userId = UUID.randomUUID();
    private final String versionKey = "auth:ver:";
    private final String jtiKey = "auth:revoked:jti:";

    @BeforeEach
    void setUp() {
        service = new TokenRevocationService(redis, userRepository);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void versionMismatch_blocks() {
        when(valueOps.get(versionKey + userId)).thenReturn("3");

        assertTrue(service.isBlocked(userId, "jti-1", 2L));
    }

    @Test
    void versionMatchWithoutBlacklist_allows() {
        when(valueOps.get(versionKey + userId)).thenReturn("2");
        when(redis.hasKey(jtiKey + "jti-1")).thenReturn(false);

        assertFalse(service.isBlocked(userId, "jti-1", 2L));
    }

    @Test
    void jtiBlacklist_blocksSingleSession() {
        when(valueOps.get(versionKey + userId)).thenReturn("2");
        when(redis.hasKey(jtiKey + "jti-1")).thenReturn(true);

        assertTrue(service.isBlocked(userId, "jti-1", 2L));
        // 另一个会话未命中 jti 黑名单 → 放行（登出只影响当前会话）
        assertFalse(service.isBlocked(userId, "jti-2", 2L));
    }

    @Test
    void cacheMiss_readsDbAndBackfills() {
        when(valueOps.get(versionKey + userId)).thenReturn(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(3L)));
        when(redis.hasKey(jtiKey + "jti-1")).thenReturn(false);

        assertFalse(service.isBlocked(userId, "jti-1", 3L));
        verify(valueOps).set(eq(versionKey + userId), eq("3"), any(Duration.class));
    }

    @Test
    void cacheMissWithDifferentDbVersion_blocks() {
        when(valueOps.get(versionKey + userId)).thenReturn(null);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(4L)));

        assertTrue(service.isBlocked(userId, "jti-1", 3L));
    }

    @Test
    void deletedUser_blocks() {
        when(valueOps.get(versionKey + userId)).thenReturn(null);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertTrue(service.isBlocked(userId, "jti-1", 0L));
    }

    @Test
    void redisDown_fallsBackToDbAndStillEnforcesVersion() {
        when(valueOps.get(versionKey + userId)).thenThrow(new RuntimeException("redis down"));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(5L)));

        // 版本一致 → 放行（修复了原来 Redis 异常就整体失效的问题）
        assertFalse(service.isBlocked(userId, "jti-1", 5L));
        // 版本不一致 → 仍然拒绝
        assertTrue(service.isBlocked(userId, "jti-1", 4L));
    }

    @Test
    void redisAndDbDown_denies() {
        when(valueOps.get(versionKey + userId)).thenThrow(new RuntimeException("redis down"));
        when(userRepository.findById(userId)).thenThrow(new RuntimeException("db down"));

        assertTrue(service.isBlocked(userId, "jti-1", 0L));
    }

    @Test
    void nullUser_blocks() {
        assertTrue(service.isBlocked(null, "jti-1", 0L));
    }

    private User user(long version) {
        return User.builder().id(userId).tokenVersion(version).build();
    }
}
