package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 企业实体类
 *
 * <p>对应 company 表，存储企业基本信息及品牌配置。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "company")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Company {

    /**
     * 企业唯一标识
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 企业名称
     */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * Logo URL
     */
    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    /**
     * 品牌色（十六进制）
     */
    @Column(name = "brand_color", length = 7)
    private String brandColor;

    // ═══════════════════════════════════════════════════════════
    // 企业合作管理字段（V32）
    // ═══════════════════════════════════════════════════════════

    /** 联系人 */
    @Column(name = "contact_name", length = 100)
    private String contactName;

    /** 联系电话 */
    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    /** 联系邮箱 */
    @Column(name = "contact_email", length = 200)
    private String contactEmail;

    /** 企业地址 */
    @Column(length = 500)
    private String address;

    /** 所属行业 */
    @Column(length = 100)
    private String industry;

    /** 企业规模 */
    @Column(length = 50)
    private String scale;

    /** 备注 */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** 状态：active=合作中, archived=已归档 */
    @Column(length = 20)
    private String status;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 更新前自动设置更新时间
     */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
