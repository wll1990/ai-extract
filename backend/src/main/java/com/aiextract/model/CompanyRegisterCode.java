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
 * 企业注册码实体类
 *
 * <p>对应 company_register_code 表。管理员生成注册码，新员工扫码注册自动归入企业。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-24
 */
@Entity
@Table(name = "company_register_code")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyRegisterCode {

    @Id
    private UUID id;

    /** 所属企业ID */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** 注册码（全局唯一） */
    @Column(nullable = false, length = 20, unique = true)
    private String code;

    /** 是否启用 */
    @Column(nullable = false)
    private Boolean enabled;

    /** 最大使用次数（0=不限） */
    @Column(name = "max_uses")
    private Integer maxUses;

    /** 已使用次数 */
    @Column(name = "used_count")
    private Integer usedCount;

    /** 创建人（管理员 userId） */
    @Column(name = "created_by")
    private UUID createdBy;

    /** 创建时间 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 过期时间 */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** 此注册码创建的用户的默认角色（employee / company_admin） */
    @Column(name = "default_role", length = 20)
    private String defaultRole;
}
