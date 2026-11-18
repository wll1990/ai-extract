package com.aiextract.service.session;

import com.aiextract.config.RolePermissions;
import com.aiextract.model.AuthSession;
import com.aiextract.model.User;
import com.aiextract.repository.AuthSessionRepository;
import com.aiextract.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 不透明会话服务 —— Redis 主读 + PostgreSQL 兜底。
 *
 * <p>安全模型：token 是 32 字节随机数，服务端只存 SHA-256；Redis/DB 泄露都拿不到可用凭证。
 * 撤销：单会话删 key；全端用用户级 epoch 失效，不依赖逐条 DEL。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private static final String READ_LUA = """
            local exp = tonumber(redis.call('HGET', KEYS[1], 'exp') or '0')
            local now = tonumber(ARGV[1])
            if exp > 0 and exp <= now then
              redis.call('DEL', KEYS[1])
              return nil
            end
            local epoch = redis.call('GET', KEYS[2])
            local sessEpoch = redis.call('HGET', KEYS[1], 'epoch')
            if epoch and sessEpoch and epoch ~= sessEpoch then
              redis.call('DEL', KEYS[1])
              return nil
            end
            local v = redis.call('HGETALL', KEYS[1])
            if #v == 0 then return nil end
            local ttl = redis.call('TTL', KEYS[1])
            local idle = tonumber(redis.call('HGET', KEYS[1], 'idle') or ARGV[2])
            local threshold = math.floor(idle * tonumber(ARGV[3]))
            if ttl >= 0 and ttl < threshold then
              local remaining = math.floor((exp - now) / 1000)
              local t = math.min(idle, remaining)
              if t > 0 then
                redis.call('EXPIRE', KEYS[1], t)
                redis.call('HSET', KEYS[1], 'seen', ARGV[1])
              end
            end
            return v
            """;

    private static final DefaultRedisScript<List> READ_SCRIPT =
            new DefaultRedisScript<>(READ_LUA, List.class);

    private static final int MAX_NEGATIVE_CACHE = 10_000;

    private final StringRedisTemplate redis;
    private final AuthSessionRepository sessions;
    private final UserRepository users;

    @Value("${app.auth.session.b-end-idle-ttl:2h}")
    private Duration bEndIdleTtl;
    @Value("${app.auth.session.b-end-absolute-ttl:12h}")
    private Duration bEndAbsoluteTtl;
    @Value("${app.auth.session.c-guest-idle-ttl:7d}")
    private Duration cGuestIdleTtl;
    @Value("${app.auth.session.c-user-idle-ttl:30d}")
    private Duration cUserIdleTtl;
    @Value("${app.auth.session.c-absolute-ttl:90d}")
    private Duration cAbsoluteTtl;
    @Value("${app.auth.session.max-devices-per-user:5}")
    private int maxDevices;
    @Value("${app.auth.session.single-session:false}")
    private boolean singleSession;
    @Value("${app.auth.session.touch-threshold:0.5}")
    private double touchThreshold;
    @Value("${app.auth.session.negative-cache-seconds:5}")
    private long negativeCacheSeconds;

    /** 进程内负缓存：随机 token 打 DB 的兜底限流。多副本各自一份，网关限流仍是第一道。 */
    private final ConcurrentHashMap<String, Long> negativeCache = new ConcurrentHashMap<>();

    /** Redis 熔断：连续失败达到阈值后，冷却窗口内直接回源 DB */
    private static final int REDIS_FAILURE_THRESHOLD = 2;
    private static final Duration REDIS_COOLDOWN = Duration.ofSeconds(10);
    private final java.util.concurrent.atomic.AtomicInteger redisFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile long redisDownUntil = 0L;

    // ==================== 创建 ====================

    @Transactional(rollbackFor = Exception.class)
    public CreatedSession create(User user, UUID partnerAppId, String externalUserId) {
        return create(user, partnerAppId, externalUserId, null, null, null);
    }

    /** 合作方按 app 配置覆盖 TTL；传 null 则使用角色默认值。 */
    @Transactional(rollbackFor = Exception.class)
    public CreatedSession create(User user, UUID partnerAppId, String externalUserId,
                                 Duration idleOverride, Duration absoluteOverride) {
        return create(user, partnerAppId, externalUserId, idleOverride, absoluteOverride, null);
    }

    /** 合作方可显式传 deviceId（比 UA 派生更稳定）。 */
    @Transactional(rollbackFor = Exception.class)
    public CreatedSession create(User user, UUID partnerAppId, String externalUserId,
                                 Duration idleOverride, Duration absoluteOverride,
                                 String deviceIdOverride) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("user required");
        }
        Instant now = Instant.now();
        Ttl ttl = idleOverride != null && absoluteOverride != null
                ? new Ttl(idleOverride, absoluteOverride)
                : ttlFor(user.getRole());
        String token = newToken();
        String hash = hash(token);
        UUID sessionId = UUID.randomUUID();
        HttpServletRequest request = currentRequest();
        String userAgent = truncate(request != null ? request.getHeader("User-Agent") : null, 512);
        String ip = truncate(resolveIp(request), 64);
        String deviceId = deviceIdOverride != null && !deviceIdOverride.isBlank()
                ? truncate(deviceIdOverride, 64)
                : truncate(deviceId(userAgent), 64);
        long epoch = user.getTokenVersion() == null ? 0L : user.getTokenVersion();

        List<AuthSession> active = sessions
                .findByUserIdAndRevokedAtIsNullAndAbsoluteExpiresAtAfterOrderByCreatedAtDesc(user.getId(), now);
        List<AuthSession> evicted = new ArrayList<>();
        if (singleSession) {
            evicted.addAll(active);
        } else if (active.size() >= maxDevices) {
            evicted.addAll(active.subList(Math.max(0, maxDevices - 1), active.size()));
        }
        for (AuthSession old : evicted) {
            old.setRevokedAt(now);
            old.setRevokedReason("device_evicted");
        }
        if (!evicted.isEmpty()) {
            sessions.saveAll(evicted);
        }

        AuthSession row = AuthSession.builder()
                .id(sessionId)
                .tokenHash(hash)
                .userId(user.getId())
                .companyId(user.getCompanyId())
                .role(user.getRole())
                .epoch(epoch)
                .mustChangePassword(Boolean.TRUE.equals(user.getMustChangePassword()))
                .partnerAppId(partnerAppId)
                .externalUserId(externalUserId)
                .deviceId(deviceId)
                .userAgent(userAgent)
                .ip(ip)
                .idleTtlSeconds((int) ttl.idle().getSeconds())
                .createdAt(now)
                .lastSeenAt(now)
                .absoluteExpiresAt(now.plus(ttl.absolute()))
                .build();
        sessions.save(row);

        List<AuthSession> evictedSnapshot = List.copyOf(evicted);
        AuthSession rowSnapshot = row;
        runAfterCommit(() -> {
            writeRedis(rowSnapshot, hash,
                    Math.min(ttl.idle().getSeconds(), ttl.absolute().getSeconds()));
            for (AuthSession old : evictedSnapshot) {
                runRedis(() -> deleteRedis(old.getUserId(), old.getTokenHash()),
                        "device_evicted:" + old.getId());
            }
        });

        return new CreatedSession(token, toInfo(row));
    }

    // ==================== 读取 ====================

    /**
     * 读取会话。
     *
     * @return null = 无效/过期；非 null = 有效
     * @throws SessionUnavailableException Redis 与 DB 均不可用（调用方返回 503）
     */
    public SessionInfo get(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String hash = hash(rawToken);
        Lookup lookup = readRedis(hash);
        if (lookup.info() != null) {
            return lookup.info();
        }
        if (!lookup.redisError() && isRecentlyMissed(hash)) {
            return null;
        }
        try {
            Optional<AuthSession> row = sessions
                    .findByTokenHashAndRevokedAtIsNullAndAbsoluteExpiresAtAfter(hash, Instant.now());
            if (row.isEmpty()) {
                if (!lookup.redisError()) {
                    rememberMiss(hash);
                }
                return null;
            }
            SessionInfo info = toInfo(row.get()).degradedCopy();
            backfill(hash, row.get());
            return info;
        } catch (Exception e) {
            throw new SessionUnavailableException("会话存储不可用", e);
        }
    }

    // ==================== 撤销 ====================

    @Transactional(rollbackFor = Exception.class)
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        String hash = hash(rawToken);
        Optional<AuthSession> found = sessions.findByTokenHash(hash);
        if (found.isEmpty()) {
            return;
        }
        AuthSession row = found.get();
        if (row.getRevokedAt() == null) {
            row.setRevokedAt(Instant.now());
            row.setRevokedReason("logout");
            sessions.save(row);
        }
        UUID userId = row.getUserId();
        runAfterCommit(() -> runRedis(() -> deleteRedis(userId, hash), "logout:" + row.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void revokeSession(UUID userId, UUID sessionId) {
        AuthSession row = sessions.findById(sessionId)
                .filter(s -> userId.equals(s.getUserId()))
                .orElse(null);
        if (row == null) {
            return;
        }
        if (row.getRevokedAt() == null) {
            row.setRevokedAt(Instant.now());
            row.setRevokedReason("kicked");
            sessions.save(row);
        }
        String hash = row.getTokenHash();
        runAfterCommit(() -> runRedis(() -> deleteRedis(userId, hash), "kick:" + sessionId));
    }

    @Transactional(rollbackFor = Exception.class)
    public void revokeOthers(UUID userId, String currentToken) {
        String currentHash = currentToken == null || currentToken.isBlank() ? null : hash(currentToken);
        List<AuthSession> active = sessions
                .findByUserIdAndRevokedAtIsNullAndAbsoluteExpiresAtAfterOrderByCreatedAtDesc(userId, Instant.now());
        Instant now = Instant.now();
        List<String> hashes = new ArrayList<>();
        for (AuthSession row : active) {
            if (currentHash != null && currentHash.equals(row.getTokenHash())) {
                continue;
            }
            row.setRevokedAt(now);
            row.setRevokedReason("revoke_others");
            hashes.add(row.getTokenHash());
        }
        if (!hashes.isEmpty()) {
            sessions.saveAll(active);
            runAfterCommit(() -> hashes.forEach(h ->
                    runRedis(() -> deleteRedis(userId, h), "revoke_others:" + h)));
        }
    }

    /** 全端撤销：DB 版本 +1，Redis epoch 失效所有会话。 */
    @Transactional(rollbackFor = Exception.class)
    public void revokeAll(UUID userId) {
        int updated = users.bumpTokenVersion(userId);
        if (updated == 0) {
            return;
        }
        Long newVersion = users.findTokenVersionById(userId);
        sessions.revokeAllByUserId(userId, Instant.now(), "revoke_all");
        if (newVersion != null) {
            runAfterCommit(() -> runRedis(() -> writeEpoch(userId, newVersion), "revoke_all:" + userId));
        }
    }

    /** 调用方已在业务事务内 bump 过 token_version，这里只同步会话与 Redis。 */
    @Transactional(rollbackFor = Exception.class)
    public void revokeAllWithEpoch(UUID userId, long epoch) {
        sessions.revokeAllByUserId(userId, Instant.now(), "authorities_changed");
        runAfterCommit(() -> runRedis(() -> writeEpoch(userId, epoch), "authorities_changed:" + userId));
    }

    // ==================== 设备列表 ====================

    public List<DeviceView> listDevices(UUID userId, String currentToken) {
        String currentHash = currentToken == null || currentToken.isBlank() ? null : hash(currentToken);
        return sessions
                .findByUserIdAndRevokedAtIsNullAndAbsoluteExpiresAtAfterOrderByCreatedAtDesc(userId, Instant.now())
                .stream()
                .map(s -> new DeviceView(
                        s.getId(),
                        s.getDeviceId(),
                        s.getUserAgent(),
                        s.getIp(),
                        s.getCreatedAt(),
                        lastSeenAt(userId, s.getTokenHash(), s.getLastSeenAt()),
                        currentHash != null && currentHash.equals(s.getTokenHash())))
                .toList();
    }

    /** 优先读 Redis 的 seen（每次续期会更新），Redis 不可用才回退 DB 登录时间。 */
    public Instant lastSeenAt(UUID userId, String tokenHash, Instant fallback) {
        try {
            Object seen = redis.opsForHash().get(sessKey(userId, tokenHash), "seen");
            if (seen != null) {
                return Instant.ofEpochMilli(Long.parseLong(seen.toString()));
            }
        } catch (Exception e) {
            log.debug("读取会话 seen 失败，回退 DB: {}", e.toString());
        }
        return fallback;
    }

    public record DeviceView(UUID sessionId, String deviceId, String userAgent, String ip,
                             Instant createdAt, Instant lastSeenAt, boolean current) {
    }

    // ==================== Redis 读写 ====================

    @SuppressWarnings("unchecked")
    private Lookup readRedis(String hash) {
        if (System.currentTimeMillis() < redisDownUntil) {
            return new Lookup(null, true);
        }
        try {
            String uid = redis.opsForValue().get(tokKey(hash));
            if (uid == null) {
                return new Lookup(null, false);
            }
            UUID userId = UUID.fromString(uid);
            List<String> raw = redis.execute(READ_SCRIPT,
                    List.of(sessKey(userId, hash), epochKey(userId)),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(bEndIdleTtl.getSeconds()),
                    String.valueOf(touchThreshold));
            redisFailures.set(0);
            if (raw == null || raw.isEmpty()) {
                return new Lookup(null, false);
            }
            return new Lookup(fromMap(raw, hash), false);
        } catch (Exception e) {
            recordRedisFailure(e);
            return new Lookup(null, true);
        }
    }

    /** 连续失败即熔断：冷却窗口内直接回源 DB，避免每个请求都等满命令超时。 */
    private void recordRedisFailure(Exception e) {
        if (redisFailures.incrementAndGet() >= REDIS_FAILURE_THRESHOLD) {
            redisDownUntil = System.currentTimeMillis() + REDIS_COOLDOWN.toMillis();
            redisFailures.set(0);
            log.warn("Redis 连续失败，{}s 内跳过 Redis 直接回源 DB: {}",
                    REDIS_COOLDOWN.toSeconds(), e.toString());
        } else {
            log.warn("会话读 Redis 失败，回源 DB: {}", e.toString());
        }
    }

    private void writeRedis(AuthSession row, String hash, long ttlSeconds) {
        try {
            UUID userId = row.getUserId();
            String uid = userId.toString();
            long safeTtl = Math.max(1, ttlSeconds);
            // 索引 key 只在绝对过期时消失：滑动续期只发生在会话 hash 上（同 slot Lua），
            // 若这里也用 idle TTL，活跃会话会在首个 idle 窗口后被误判未命中而反复回源 DB。
            long absoluteRemaining = Math.max(1,
                    row.getAbsoluteExpiresAt().getEpochSecond() - Instant.now().getEpochSecond());
            redis.opsForValue().set(tokKey(hash), uid, Duration.ofSeconds(absoluteRemaining));

            Map<String, String> values = new LinkedHashMap<>();
            values.put("sid", row.getId().toString());
            values.put("h", hash);
            values.put("uid", uid);
            if (row.getCompanyId() != null) {
                values.put("cid", row.getCompanyId().toString());
            }
            values.put("role", row.getRole());
            values.put("mcp", Boolean.TRUE.equals(row.getMustChangePassword()) ? "1" : "0");
            values.put("epoch", String.valueOf(row.getEpoch() == null ? 0L : row.getEpoch()));
            if (row.getPartnerAppId() != null) {
                values.put("pis", row.getPartnerAppId().toString());
            }
            if (row.getExternalUserId() != null) {
                values.put("euid", row.getExternalUserId());
            }
            if (row.getDeviceId() != null) {
                values.put("dev", row.getDeviceId());
            }
            if (row.getUserAgent() != null) {
                values.put("ua", truncate(row.getUserAgent(), 512));
            }
            if (row.getIp() != null) {
                values.put("ip", row.getIp());
            }
            values.put("iat", String.valueOf(row.getCreatedAt().toEpochMilli()));
            values.put("exp", String.valueOf(row.getAbsoluteExpiresAt().toEpochMilli()));
            values.put("idle", String.valueOf(row.getIdleTtlSeconds()));
            values.put("seen", String.valueOf(row.getLastSeenAt().toEpochMilli()));

            String sessionKey = sessKey(userId, hash);
            redis.opsForHash().putAll(sessionKey, values);
            redis.expire(sessionKey, Duration.ofSeconds(safeTtl));

            redis.opsForValue().setIfAbsent(epochKey(userId),
                    String.valueOf(row.getEpoch() == null ? 0L : row.getEpoch()));

            String indexKey = indexKey(userId);
            redis.opsForZSet().add(indexKey, hash, row.getCreatedAt().toEpochMilli());
            long remaining = row.getAbsoluteExpiresAt().getEpochSecond() - Instant.now().getEpochSecond();
            redis.expire(indexKey, Duration.ofSeconds(Math.max(60, remaining + 3600)));
        } catch (Exception e) {
            log.warn("会话写 Redis 失败（DB 已是真相源）userId={}: {}",
                    row.getUserId(), e.toString());
        }
    }

    private void backfill(String hash, AuthSession row) {
        if (System.currentTimeMillis() < redisDownUntil) {
            return;
        }
        long remaining = row.getAbsoluteExpiresAt().getEpochSecond() - Instant.now().getEpochSecond();
        if (remaining <= 0) {
            return;
        }
        long idle = row.getIdleTtlSeconds() == null
                ? bEndIdleTtl.getSeconds() : row.getIdleTtlSeconds();
        writeRedis(row, hash, Math.min(idle, remaining));
    }

    /** 失败直接抛出，由 {@link #runRedis} 重试并进入补偿队列。 */
    private void deleteRedis(UUID userId, String hash) {
        redis.delete(tokKey(hash));
        redis.delete(sessKey(userId, hash));
        redis.opsForZSet().remove(indexKey(userId), hash);
    }

    /** 失败直接抛出，由 {@link #runRedis} 重试并进入补偿队列。 */
    private void writeEpoch(UUID userId, long epoch) {
        redis.opsForValue().set("auth:ver:" + userId, String.valueOf(epoch), Duration.ofSeconds(60));
        redis.opsForValue().set(epochKey(userId), String.valueOf(epoch));
        redis.delete(indexKey(userId));
    }

    // ==================== 辅助 ====================

    private Ttl ttlFor(String role) {
        if (RolePermissions.C_GUEST.equals(role)) {
            return new Ttl(cGuestIdleTtl, cAbsoluteTtl);
        }
        if (RolePermissions.C_USER.equals(role) || RolePermissions.C_PARTNER.equals(role)) {
            return new Ttl(cUserIdleTtl, cAbsoluteTtl);
        }
        return new Ttl(bEndIdleTtl, bEndAbsoluteTtl);
    }

    private record Ttl(Duration idle, Duration absolute) {
    }

    private record Lookup(SessionInfo info, boolean redisError) {
    }

    private SessionInfo fromMap(List<String> raw, String hash) {
        Map<String, String> m = new HashMap<>(raw.size() / 2 + 1);
        for (int i = 0; i + 1 < raw.size(); i += 2) {
            m.put(raw.get(i), raw.get(i + 1));
        }
        return new SessionInfo(
                uuid(m.get("sid")),
                uuid(m.get("uid")),
                uuid(m.get("cid")),
                m.get("role"),
                "1".equals(m.get("mcp")),
                parseLong(m.get("epoch")),
                uuid(m.get("pis")),
                m.get("euid"),
                m.get("dev"),
                m.get("ua"),
                m.get("ip"),
                instant(m.get("iat")),
                instant(m.get("seen")),
                instant(m.get("exp")),
                hash,
                false);
    }

    private SessionInfo toInfo(AuthSession row) {
        return new SessionInfo(
                row.getId(),
                row.getUserId(),
                row.getCompanyId(),
                row.getRole(),
                Boolean.TRUE.equals(row.getMustChangePassword()),
                row.getEpoch() == null ? 0L : row.getEpoch(),
                row.getPartnerAppId(),
                row.getExternalUserId(),
                row.getDeviceId(),
                row.getUserAgent(),
                row.getIp(),
                row.getCreatedAt(),
                row.getLastSeenAt(),
                row.getAbsoluteExpiresAt(),
                row.getTokenHash(),
                false);
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String tokKey(String hash) {
        return "auth:tok:" + hash;
    }

    private static String sessKey(UUID userId, String hash) {
        return "auth:{u:" + userId + "}:sess:" + hash;
    }

    private static String indexKey(UUID userId) {
        return "auth:{u:" + userId + "}:sessions";
    }

    private static String epochKey(UUID userId) {
        return "auth:{u:" + userId + "}:epoch";
    }

    private HttpServletRequest currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }

    private static String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private static String deviceId(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "unknown";
        }
        return hash(userAgent).substring(0, 16);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Instant instant(String millis) {
        long value = parseLong(millis);
        return value <= 0 ? null : Instant.ofEpochMilli(value);
    }

    private boolean isRecentlyMissed(String hash) {
        Long at = negativeCache.get(hash);
        if (at == null) {
            return false;
        }
        if (System.currentTimeMillis() - at < negativeCacheSeconds * 1000L) {
            return true;
        }
        negativeCache.remove(hash);
        return false;
    }

    private void rememberMiss(String hash) {
        if (negativeCache.size() > MAX_NEGATIVE_CACHE) {
            negativeCache.clear();
        }
        negativeCache.put(hash, System.currentTimeMillis());
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    // ==================== Redis 故障补偿 ====================

    /**
     * 撤销/封禁的传播依赖 Redis 写入成功：DB 已 revoked 时，Redis 命中的请求不会回源。
     * 因此失败先在本进程退避重试，仍失败进入短期补偿队列，由定时任务继续重放。
     * 队列是进程内的：实例崩溃会丢失（DB 仍是真相源，但 Redis 里的旧会话要等 TTL）；
     * 多实例部署需要换成 outbox 表才能做到跨进程不丢。
     */
    private static final int REDIS_RETRY_ATTEMPTS = 3;
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);
    private static final int MAX_PENDING = 10_000;

    private final ConcurrentLinkedQueue<PendingOp> pendingOps = new ConcurrentLinkedQueue<>();

    private record PendingOp(Runnable action, String description, Instant expiresAt) {
    }

    private void runRedis(Runnable action, String description) {
        if (System.currentTimeMillis() < redisDownUntil) {
            enqueue(action, description);
            return;
        }
        Exception last = null;
        for (int attempt = 1; attempt <= REDIS_RETRY_ATTEMPTS; attempt++) {
            try {
                action.run();
                redisFailures.set(0);
                return;
            } catch (Exception e) {
                last = e;
                recordRedisFailure(e);
                if (System.currentTimeMillis() < redisDownUntil) {
                    break;
                }
                if (attempt < REDIS_RETRY_ATTEMPTS) {
                    sleepQuietly(50L * attempt);
                }
            }
        }
        log.error("会话 Redis 操作失败，进入补偿队列 desc={}: {}", description,
                last == null ? "" : last.toString());
        enqueue(action, description);
    }

    private void enqueue(Runnable action, String description) {
        if (pendingOps.size() >= MAX_PENDING) {
            log.error("会话 Redis 补偿队列已满，丢弃 desc={}", description);
            return;
        }
        pendingOps.add(new PendingOp(action, description, Instant.now().plus(PENDING_TTL)));
    }

    /** 由定时任务调用：重放失败的撤销动作，超过保留期仍失败则告警放弃。 */
    public void retryPendingRedisOps() {
        if (System.currentTimeMillis() < redisDownUntil) {
            return;
        }
        int size = pendingOps.size();
        for (int i = 0; i < size; i++) {
            PendingOp op = pendingOps.poll();
            if (op == null) {
                return;
            }
            if (System.currentTimeMillis() < redisDownUntil) {
                pendingOps.add(op);
                continue;
            }
            try {
                op.action().run();
                redisFailures.set(0);
            } catch (Exception e) {
                recordRedisFailure(e);
                if (Instant.now().isBefore(op.expiresAt())) {
                    pendingOps.add(op);
                } else {
                    log.error("会话 Redis 补偿超时放弃 desc={}: {}", op.description(), e.toString());
                }
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
