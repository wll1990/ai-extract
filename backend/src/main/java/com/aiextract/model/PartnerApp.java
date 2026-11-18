package com.aiextract.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "partner_app")
@Data @Builder
@NoArgsConstructor @AllArgsConstructor
public class PartnerApp {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 合作方标识，唯一，如 "alibaba" */
    @Column(nullable = false, unique = true, length = 50)
    private String appId;

    /** 显示名称，如 "阿里云" */
    @Column(nullable = false, length = 100)
    private String appName;

    /** AES-256-GCM 加密存储的 SK */
    @Column(name = "secret_key", nullable = false, length = 500)
    private String secretKey;

    /** 过渡期旧 SK — 24h 内新旧都有效 */
    @Column(name = "old_secret_key", length = 500)
    private String oldSecretKey;

    @Column(name = "old_key_expires_at")
    private LocalDateTime oldKeyExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PartnerStatus status;

    @Column(name = "contact_name", length = 50)
    private String contactName;

    @Column(name = "contact_email", length = 100)
    private String contactEmail;

    /** 一次性 code 有效期（秒） */
    @Builder.Default
    @Column(name = "code_ttl_seconds", nullable = false)
    private Integer codeTtlSeconds = 60;

    /** 合作方会话闲置 TTL（秒） */
    @Builder.Default
    @Column(name = "session_idle_ttl_seconds", nullable = false)
    private Integer sessionIdleTtlSeconds = 7200;

    /** 合作方会话绝对 TTL（秒） */
    @Builder.Default
    @Column(name = "session_absolute_ttl_seconds", nullable = false)
    private Integer sessionAbsoluteTtlSeconds = 43200;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum PartnerStatus {
        ENABLED, DISABLED
    }
}
