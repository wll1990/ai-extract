package com.aiextract.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 复盘追问额度：每会话总条数 + 每分钟速率。Redis 异常 fail-open。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesReviewRateLimiter {

    private final StringRedisTemplate redisTemplate;

    @Value("${app.sales.review.chat.max-total-per-session:40}")
    private int maxTotal;

    @Value("${app.sales.review.chat.max-per-minute:10}")
    private int maxPerMinute;

    private static final DefaultRedisScript<Long> TOTAL_SCRIPT = new DefaultRedisScript<>(
            "local k = KEYS[1] "
                    + "local init = tonumber(ARGV[1]) "
                    + "local limit = tonumber(ARGV[2]) "
                    + "if redis.call('EXISTS', k) == 0 and init > 0 then "
                    + "  redis.call('SET', k, init, 'EX', 86400) "
                    + "end "
                    + "local n = redis.call('INCR', k) "
                    + "if redis.call('TTL', k) < 0 then redis.call('EXPIRE', k, 86400) end "
                    + "if n <= limit then return 1 else return 0 end",
            Long.class);

    private static final DefaultRedisScript<Long> MINUTE_SCRIPT = new DefaultRedisScript<>(
            "local n = redis.call('INCR', KEYS[1]) "
                    + "if n == 1 then redis.call('EXPIRE', KEYS[1], 60) end "
                    + "if n <= tonumber(ARGV[1]) then return 1 else return 0 end",
            Long.class);

    public boolean tryConsume(UUID sessionId, long persistedUserMessages) {
        try {
            // 先判分钟速率：被分钟限流时不能扣掉总次数额度
            Long perMinute = redisTemplate.execute(MINUTE_SCRIPT,
                    List.of("rate:sales-review-min:" + sessionId),
                    String.valueOf(maxPerMinute));
            if (perMinute != null && perMinute == 0L) {
                return false;
            }
            Long total = redisTemplate.execute(TOTAL_SCRIPT,
                    List.of("rate:sales-review-total:" + sessionId),
                    String.valueOf(Math.max(persistedUserMessages, 0)),
                    String.valueOf(maxTotal));
            return total == null || total == 1L;
        } catch (Exception e) {
            log.warn("复盘追问限流 Redis 异常，回退 DB 计数 sessionId={}: {}",
                    sessionId, e.getMessage());
            return persistedUserMessages < maxTotal;
        }
    }
}
