package com.aiextract.config;

import com.aiextract.repository.UserRepository;
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
 * <p>无状态 JWT 无法主动失效，靠两层机制：</p>
 * <ol>
 *   <li><b>安全版本号</b>：用户表 {@code token_version} 是真相，Redis {@code auth:ver:{userId}} 是读加速。
 *       改角色/停用后版本 +1，旧 token 的 {@code ver} claim 与之不符即失效；
 *       这也是「踢掉某用户全部设备」的唯一手段（版本 +1 覆盖所有已签发 token）。</li>
 *   <li><b>单会话黑名单</b>：{@code auth:revoked:jti:{jti}}，登出当前会话用。</li>
 * </ol>
 *
 * <p><b>失败策略</b>：Redis 异常时回源 DB 读版本号，只有 DB 也不可用才拒绝请求。
 * 这样既修掉了原来「Redis 抖动 → 撤销整体失效」的 fail-open，又不会让一次 Redis 抖动
 * 把全站登录打成 401。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    private static final String KEY_JTI = "auth:revoked:jti:";
    private static final String KEY_VER = "auth:ver:";

    /**
     * 版本号缓存 TTL。
     *
     * <p>不能太长：DB 是真相，Redis 只是读加速。若权限变更时 Redis 写失败、或运维直接改库，
     * 过长的 TTL 会让**旧缓存压住新版本**（旧 token 被误放行 / 新 token 被误拒）。
     * 正常路径在事务提交后会主动写 Redis（秒级生效），60 秒只是兜住异常路径，
     * 代价是活跃用户每分钟最多一次 DB 回源。</p>
     */
    private static final Duration VERSION_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;

    /** fail-open/降级计数，用于告警 */
    private final AtomicLong degradedCount = new AtomicLong();

    /** 撤销单个 token（登出当前会话） */
    public void revokeToken(String jti, long ttlSeconds) {
        if (jti == null) return;
        redis.opsForValue().set(KEY_JTI + jti, "1", Duration.ofSeconds(ttlSeconds));
    }

    /** 写入版本号缓存（权限变更提交后调用；写失败只影响下一次读的性能，不影响正确性） */
    public void cacheVersion(UUID userId, long tokenVersion) {
        if (userId == null) return;
        redis.opsForValue().set(KEY_VER + userId, String.valueOf(tokenVersion), VERSION_TTL);
    }

    /**
     * 是否应拒绝该 token。
     *
     * <p>判定顺序：版本号 → jti 黑名单。Redis 正常时全程不碰 DB；
     * 版本号缓存未命中（改造前签发的 token、新用户）回源一次并回填。</p>
     *
     * @param userId       用户 ID
     * @param jti          会话标识（可空）
     * @param tokenVersion token 里携带的版本号（无 claim 时为 0）
     * @return true = 拒绝（401），false = 放行
     */
    public boolean isBlocked(UUID userId, String jti, long tokenVersion) {
        if (userId == null) {
            return true;
        }
        try {
            String cached = redis.opsForValue().get(KEY_VER + userId);
            if (cached != null) {
                return Long.parseLong(cached) != tokenVersion || isJtiRevoked(jti);
            }
            // 缓存未命中：回源 DB（改造前 token / 首次请求），并回填
            Long dbVersion = loadVersionFromDb(userId);
            if (dbVersion == null) {
                return true; // 用户已不存在
            }
            cacheVersion(userId, dbVersion);
            return dbVersion != tokenVersion || isJtiRevoked(jti);
        } catch (Exception redisError) {
            long n = degradedCount.incrementAndGet();
            if (n == 1 || n % 1000 == 0) {
                log.warn("Token 撤销检查降级：Redis 不可用，回源 DB（第 {} 次）", n, redisError);
            }
            try {
                Long dbVersion = loadVersionFromDb(userId);
                if (dbVersion == null) {
                    return true;
                }
                // Redis 不可用期间读不到 jti 黑名单，但版本号来自 DB，
                // 已被 bump（停用/降权/踢全端）的旧 token 依然会被拦住
                return dbVersion != tokenVersion;
            } catch (Exception dbError) {
                log.error("Token 撤销检查失败：Redis 与 DB 均不可用，拒绝请求 userId={}", userId, dbError);
                return true;
            }
        }
    }

    // ==================== 内部 ====================

    private boolean isJtiRevoked(String jti) {
        return jti != null && Boolean.TRUE.equals(redis.hasKey(KEY_JTI + jti));
    }

    /** 回源 DB 读安全版本号；用户不存在返回 null */
    private Long loadVersionFromDb(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> u.getTokenVersion() == null ? 0L : u.getTokenVersion())
                .orElse(null);
    }
}
