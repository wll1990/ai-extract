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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 企业 AI Key 配置。
 *
 * <p>Key 只存 {@link com.aiextract.config.PartnerCrypto} 的 AES-256-GCM 密文与末 4 位；
 * 未配置或停用且允许回退时，{@code ChatClientResolver} 使用平台 Key。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-30
 */
@Entity
@Table(name = "company_ai_config")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyAiConfig {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    @Column(name = "base_url", nullable = false, length = 300)
    private String baseUrl;

    @Column(name = "api_key_cipher", nullable = false, length = 500)
    private String apiKeyCipher;

    @Column(name = "key_last4", nullable = false, length = 8)
    private String keyLast4;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    /** enabled / disabled */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "fallback_enabled", nullable = false)
    private boolean fallbackEnabled;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
