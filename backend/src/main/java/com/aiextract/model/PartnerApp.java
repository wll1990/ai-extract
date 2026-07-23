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
