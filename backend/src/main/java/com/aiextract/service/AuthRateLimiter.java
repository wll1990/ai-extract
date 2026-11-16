package com.aiextract.service;

import com.aiextract.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * 登录/注册限流 — Redis INCR + TTL 固定窗口。
 *
 * <p>按 IP 与账号双维度限制暴力破解；注册按 IP 限制批量造号。
 * Redis 异常时 fail-open（可用性优先），密码错误本身仍会失败。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRateLimiter {

    private final StringRedisTemplate redisTemplate;

    @Value("${app.auth.rate-limit.login-per-ip:30}")
    private int loginPerIp;

    @Value("${app.auth.rate-limit.login-per-account:10}")
    private int loginPerAccount;

    @Value("${app.auth.rate-limit.register-per-ip:10}")
    private int registerPerIp;

    public void checkLogin(String ip, String account) {
        allow("rate:login:ip:" + safe(ip), loginPerIp, Duration.ofMinutes(15));
        if (account != null && !account.isBlank()) {
            allow("rate:login:acct:" + safe(account.toLowerCase(Locale.ROOT)),
                    loginPerAccount, Duration.ofMinutes(15));
        }
    }

    public void checkRegister(String ip) {
        allow("rate:register:ip:" + safe(ip), registerPerIp, Duration.ofHours(1));
    }

    /** 公开查询类接口（注册码/邀请码预检）防枚举 */
    public void checkPublicLookup(String namespace, String ip) {
        allow("rate:pub:" + safe(namespace) + ":" + safe(ip), 60, Duration.ofMinutes(1));
    }

    private void allow(String key, int limit, Duration ttl) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, ttl);
            }
            if (count != null && count > limit) {
                throw new BusinessException(429, "操作过于频繁，请稍后再试");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("限流 Redis 异常，放行(fail-open) key={}: {}", key, e.getMessage());
        }
    }

    /** Redis key 不允许出现空格/冒号等会破坏命名空间的字符 */
    private String safe(String value) {
        String v = value == null ? "unknown" : value.trim();
        if (v.length() > 64) {
            v = v.substring(0, 64);
        }
        return v.replaceAll("[^a-zA-Z0-9._@-]", "_");
    }
}
