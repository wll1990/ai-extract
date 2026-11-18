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
 * 企业 AI Key 配置变更历史。
 *
 * <p>只保存可展示的元数据，不保存明文或密文 Key；用于回答
 * 「谁在什么时候改了什么、当前为什么是启用/停用」。</p>
 */
@Entity
@Table(name = "company_ai_config_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyAiConfigHistory {

    public static final String ACTION_CREATED = "created";
    public static final String ACTION_UPDATED = "updated";
    public static final String ACTION_ROTATED = "rotated";
    public static final String ACTION_ENABLED = "enabled";
    public static final String ACTION_DISABLED = "disabled";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Column(name = "provider", length = 20)
    private String provider;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "base_url", length = 300)
    private String baseUrl;

    @Column(name = "key_last4", length = 8)
    private String keyLast4;

    @Column(name = "fallback_enabled", nullable = false)
    private boolean fallbackEnabled;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_name", length = 100)
    private String actorName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
