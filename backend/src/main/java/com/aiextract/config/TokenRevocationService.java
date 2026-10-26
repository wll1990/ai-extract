package com.aiextract.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Token 撤销服务 — 高危变更（封号/登出/改权限）秒级生效。
 *
 * <p>无状态 JWT 无法主动失效，靠 Redis 黑名单在鉴权过滤器中逐请求拦截。
 * Redis 异常时 fail-open（不阻断，靠 token 自然过期兜底）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    private static final String KEY_USER = "auth:revoked:user:";
    private static final String KEY_JTI = "auth:revoked:jti:";

    private final StringRedisTemplate redis;

    /** fail-open 计数，用于告警 */
    private final AtomicLong failOpenCount = new AtomicLong();

    /** 撤销某用户的全部 token（封号/删除） */
    public void revokeUser(UUID userId, long ttlSeconds) {
        if (userId == null) return;
        redis.opsForValue().set(KEY_USER + userId, "1", Duration.ofSeconds(ttlSeconds));
    }

    /** 撤销单个 token（登出当前会话） */
    public void revokeToken(String jti, long ttlSeconds) {
        if (jti == null) return;
        redis.opsForValue().set(KEY_JTI + jti, "1", Duration.ofSeconds(ttlSeconds));
    }

    /** 判断是否已撤销。fail-open：Redis 异常时返回 false（不阻断） */
    public boolean isRevoked(UUID userId, String jti) {
        try {
            if (userId != null && Boolean.TRUE.equals(redis.hasKey(KEY_USER + userId))) return true;
            return jti != null && Boolean.TRUE.equals(redis.hasKey(KEY_JTI + jti));
        } catch (Exception e) {
            long n = failOpenCount.incrementAndGet();
            if (n == 1 || n % 1000 == 0) {
                log.error("token 撤销检查降级 fail-open 已 {} 次，请检查 Redis", n, e);
            }
            return false;
        }
    }
}
