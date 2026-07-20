package com.aiextract.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 分享链路限流器 — Redis INCR + TTL 固定窗口计数
 *
 * <p>C 端对外公开链路的防刷兜底：游客创建按 IP 限（防批量薅额度），
 * 游客消息按 userId 限（防脚本刷 AI 成本）。
 * 多实例部署天然正确（计数在共享 Redis）；Redis 异常时 fail-open 放行
 * （可用性优先，与游客免费额度的 DB 计数双层兜底）。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-19
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShareRateLimiter {

    private static final DateTimeFormatter HOUR_FMT = DateTimeFormatter.ofPattern("yyyyMMddHH");
    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final StringRedisTemplate redisTemplate;

    /** 单 IP 每小时可创建的游客数 */
    @Value("${app.share.guest-create-per-ip-hour:20}")
    private int guestCreatePerIpHour;

    /** 单游客每分钟可发送的消息数 */
    @Value("${app.share.guest-msg-per-min:6}")
    private int guestMsgPerMin;

    /**
     * 游客创建限流（按 IP，小时窗口）
     */
    public boolean allowGuestCreate(String ip) {
        String key = "rate:guest:" + ip + ":" + LocalDateTime.now().format(HOUR_FMT);
        return allow(key, guestCreatePerIpHour, Duration.ofHours(1));
    }

    /**
     * 游客消息限流（按 userId，分钟窗口）
     */
    public boolean allowGuestMessage(UUID userId) {
        String key = "rate:gmsg:" + userId + ":" + LocalDateTime.now().format(MINUTE_FMT);
        return allow(key, guestMsgPerMin, Duration.ofMinutes(1));
    }

    private boolean allow(String key, int limit, Duration ttl) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, ttl);
            }
            return count == null || count <= limit;
        } catch (Exception e) {
            // fail-open：限流是兜底而非主控制（额度另有 DB 计数），Redis 故障不应阻断业务
            log.warn("限流Redis异常，放行(fail-open) key={}: {}", key, e.getMessage());
            return true;
        }
    }
}
