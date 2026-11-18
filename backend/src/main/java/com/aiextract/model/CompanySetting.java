package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 企业级在线开关/配置。
 *
 * <p>用于需要「保存后立即生效」的业务开关：读取端每次都按
 * {@code (company_id, setting_key)} 点查，不做缓存，避免多实例下配置漂移。</p>
 *
 * <p>值统一按字符串存储，由使用方解析；非法值按各开关的默认值处理。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-16
 */
@Entity
@Table(name = "company_setting",
        uniqueConstraints = @UniqueConstraint(name = "uq_company_setting",
                columnNames = {"company_id", "setting_key"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanySetting {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "setting_key", nullable = false, length = 64)
    private String settingKey;

    @Column(name = "setting_value", nullable = false, columnDefinition = "TEXT")
    private String settingValue;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
