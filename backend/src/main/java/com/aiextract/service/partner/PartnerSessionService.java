package com.aiextract.service.partner;

import com.aiextract.dto.PartnerDtos;
import com.aiextract.exception.PartnerException;
import com.aiextract.model.AuthSession;
import com.aiextract.model.PartnerApp;
import com.aiextract.model.User;
import com.aiextract.repository.AuthSessionRepository;
import com.aiextract.repository.PartnerAppRepository;
import com.aiextract.service.session.CreatedSession;
import com.aiextract.service.session.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 三方 H5 接入：一次性 code 换会话 + 合作方维度会话治理。
 */
@Service
@RequiredArgsConstructor
public class PartnerSessionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    /** 比较并删除：只有值未被并发兑换/覆盖时才消费，保证单次使用且不在失败时烧号。 */
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PartnerAppRepository partnerAppRepository;
    private final PartnerUserService partnerUserService;
    private final SessionService sessionService;
    private final AuthSessionRepository authSessionRepository;

    public PartnerDtos.CodeResponse issueCode(PartnerApp app, PartnerDtos.CodeRequest request) {
        if (request == null || isBlank(request.externalUserId())) {
            throw new PartnerException(400, "PARTNER_ARGUMENT_INVALID", "externalUserId 不能为空");
        }
        int ttl = app.getCodeTtlSeconds() == null ? 60 : app.getCodeTtlSeconds();
        String code = newCode();
        Payload payload = new Payload(app.getAppId(), request.externalUserId().trim(),
                request.userName(), request.deviceId(), System.currentTimeMillis());
        try {
            redis.opsForValue().set(codeKey(code),
                    objectMapper.writeValueAsString(payload), Duration.ofSeconds(ttl));
        } catch (Exception e) {
            throw new IllegalStateException("code 序列化失败", e);
        }
        return new PartnerDtos.CodeResponse(code, ttl);
    }

    public PartnerDtos.ExchangeResponse exchange(String appId, String code) {
        if (isBlank(appId) || isBlank(code)) {
            throw new PartnerException(400, "PARTNER_ARGUMENT_INVALID", "appId/code 不能为空");
        }
        String json = redis.opsForValue().get(codeKey(code));
        if (json == null) {
            throw new PartnerException(401, "PARTNER_CODE_INVALID", "code 无效或已过期");
        }
        Payload payload;
        try {
            payload = objectMapper.readValue(json, Payload.class);
        } catch (Exception e) {
            throw new PartnerException(401, "PARTNER_CODE_INVALID", "code 无效");
        }
        if (!appId.equals(payload.appId())) {
            throw new PartnerException(401, "PARTNER_CODE_INVALID", "code 不属于该 appId");
        }

        PartnerApp app = partnerAppRepository.findByAppId(appId)
                .orElseThrow(PartnerException::appNotFound);
        if (app.getStatus() != PartnerApp.PartnerStatus.ENABLED) {
            throw PartnerException.appDisabled();
        }

        // 先完成可能失败的准备（用户映射），再原子消费 code：
        // 瞬时失败不会烧掉 code，H5 可以用同一个 code 重试。
        User user = partnerUserService.findOrCreate(app, payload.externalUserId(), payload.userName());
        Long consumed = redis.execute(CONSUME_SCRIPT, List.of(codeKey(code)), json);
        if (consumed == null || consumed == 0L) {
            throw new PartnerException(401, "PARTNER_CODE_INVALID", "code 无效或已使用");
        }

        Duration idle = Duration.ofSeconds(app.getSessionIdleTtlSeconds() == null
                ? 7200 : app.getSessionIdleTtlSeconds());
        Duration absolute = Duration.ofSeconds(app.getSessionAbsoluteTtlSeconds() == null
                ? 43200 : app.getSessionAbsoluteTtlSeconds());
        CreatedSession created = sessionService.create(user, UUID.fromString(app.getAppId()),
                payload.externalUserId(), idle, absolute, payload.deviceId());
        return new PartnerDtos.ExchangeResponse(created.token(), (int) absolute.getSeconds(),
                user.getId().toString(), user.getName(), payload.externalUserId());
    }

    public List<SessionService.DeviceView> list(PartnerApp app, String externalUserId) {
        if (isBlank(externalUserId)) {
            throw new PartnerException(400, "PARTNER_ARGUMENT_INVALID", "externalUserId 不能为空");
        }
        return authSessionRepository
                .findByPartnerAppIdAndExternalUserIdAndRevokedAtIsNullAndAbsoluteExpiresAtAfterOrderByCreatedAtDesc(
                        UUID.fromString(app.getAppId()), externalUserId, Instant.now())
                .stream()
                .map(s -> new SessionService.DeviceView(
                        s.getId(), s.getDeviceId(), s.getUserAgent(), s.getIp(),
                        s.getCreatedAt(),
                        sessionService.lastSeenAt(s.getUserId(), s.getTokenHash(), s.getLastSeenAt()),
                        false))
                .toList();
    }

    public void revoke(PartnerApp app, UUID sessionId) {
        AuthSession session = authSessionRepository.findById(sessionId)
                .orElse(null);
        if (session == null || session.getPartnerAppId() == null
                || !session.getPartnerAppId().toString().equals(app.getAppId())) {
            throw new PartnerException(404, "PARTNER_SESSION_NOT_FOUND", "会话不存在");
        }
        sessionService.revokeSession(session.getUserId(), sessionId);
    }

    public void revokeAll(PartnerApp app, String externalUserId) {
        User user = partnerUserService.find(app.getAppId(), externalUserId)
                .orElseThrow(() -> new PartnerException(404, "PARTNER_USER_NOT_FOUND", "用户不存在"));
        sessionService.revokeAll(user.getId());
    }

    private static String newCode() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return B64.encodeToString(bytes);
    }

    private static String codeKey(String code) {
        return "partner:code:" + sha256Hex(code);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Payload(String appId, String externalUserId, String userName,
                           String deviceId, long iat) {
    }
}
