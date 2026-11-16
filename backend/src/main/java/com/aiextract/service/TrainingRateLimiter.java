package com.aiextract.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 培训陪练的提示额度控制 —— Redis 固定计数 + TTL。
 *
 * <p>提示每次都会触发一次 LLM 调用，max_attempts 管不到「一次尝试内狂点提示」，
 * 必须在服务端设配额。多实例天然正确；Redis 异常时 fail-open（可用性优先，
 * 与 ShareRateLimiter 同口径）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingRateLimiter {

    private final StringRedisTemplate redisTemplate;

    /** 单次尝试最多可请求的提示次数 */
    @Value("${app.training.practice.max-hints-per-attempt:10}")
    private int maxHintsPerAttempt;

    /** 计数键保留 24 小时（覆盖单次尝试的生命周期） */
    private static final Duration HINT_TTL = Duration.ofHours(24);

    /**
     * 发言额度原子扣减：首次以已持久化条数初始化，之后 INCR；
     * 避免「count-then-send」并发穿透上限。
     */
    private static final DefaultRedisScript<Long> PRACTICE_MSG_SCRIPT = new DefaultRedisScript<>(
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

    /**
     * 消耗一次陪练发言额度（原子）。
     *
     * @param persistedCount 已持久化的用户消息数（Redis 冷启动时的初始值）
     * @param limit          单次尝试上限
     * @return false 表示已超上限
     */
    public boolean tryConsumePracticeMessage(UUID attemptId, long persistedCount, int limit) {
        String key = "rate:training-msg:" + attemptId;
        try {
            Long allowed = redisTemplate.execute(PRACTICE_MSG_SCRIPT, List.of(key),
                    String.valueOf(Math.max(persistedCount, 0)), String.valueOf(limit));
            return allowed == null || allowed == 1L;
        } catch (Exception e) {
            log.warn("培训发言限流 Redis 异常，回退 DB 计数 attemptId={}: {}", attemptId, e.getMessage());
            return persistedCount < limit;
        }
    }

    /**
     * 消耗一次提示额度。
     *
     * @return false 表示额度已用完
     */
    public boolean allowHint(UUID attemptId) {
        String key = "rate:training-hint:" + attemptId;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, HINT_TTL);
            }
            return count == null || count <= maxHintsPerAttempt;
        } catch (Exception e) {
            log.warn("培训提示限流 Redis 异常，放行(fail-open) attemptId={}: {}", attemptId, e.getMessage());
            return true;
        }
    }
}
