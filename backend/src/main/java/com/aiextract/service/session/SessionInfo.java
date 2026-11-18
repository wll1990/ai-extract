package com.aiextract.service.session;

import java.time.Instant;
import java.util.UUID;

/**
 * 请求级会话快照。degraded=true 表示本次从 DB 回源，未走 Redis 主路径。
 */
public record SessionInfo(
        UUID sessionId,
        UUID userId,
        UUID companyId,
        String role,
        boolean mustChangePassword,
        long epoch,
        UUID partnerAppId,
        String externalUserId,
        String deviceId,
        String userAgent,
        String ip,
        Instant createdAt,
        Instant lastSeenAt,
        Instant absoluteExpiresAt,
        String tokenHash,
        boolean degraded
) {
    public SessionInfo degradedCopy() {
        return new SessionInfo(sessionId, userId, companyId, role, mustChangePassword, epoch,
                partnerAppId, externalUserId, deviceId, userAgent, ip, createdAt, lastSeenAt,
                absoluteExpiresAt, tokenHash, true);
    }
}
