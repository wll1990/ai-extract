package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 不透明会话记录 —— 认证真相源。
 *
 * <p>Redis 是主读路径与秒级撤销通道，本表负责兜底、审计、设备列表与绝对过期。
 * token 只存 SHA-256，明文只在客户端手里。</p>
 */
@Entity
@Table(name = "auth_session")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthSession {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "company_id")
    private UUID companyId;

    @Column(name = "role", nullable = false, length = 32)
    private String role;

    /** 用户安全版本号（user.token_version 的快照） */
    @Column(name = "epoch", nullable = false)
    private Long epoch;

    @Column(name = "must_change_password", nullable = false)
    private Boolean mustChangePassword;

    @Column(name = "partner_app_id")
    private UUID partnerAppId;

    @Column(name = "external_user_id", length = 128)
    private String externalUserId;

    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "idle_ttl_seconds", nullable = false)
    private Integer idleTtlSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 64)
    private String revokedReason;
}
