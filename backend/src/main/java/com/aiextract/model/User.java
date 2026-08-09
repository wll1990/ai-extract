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
 * 用户实体类
 *
 * <p>统一用户表，承载 B 端企业用户 + C 端平台用户 + 合作方用户。
 * name 字段统一为展示名（B端真实姓名 / C端昵称），不再区分 name/nickname。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "\"user\"")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    /**
     * 用户唯一标识
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 所属企业ID
     */
    @Column(name = "company_id")
    private UUID companyId;

    /**
     * 展示名（B端真实姓名 / C端昵称，统一字段）
     */
    @Column(name = "name", length = 100)
    private String name;

    /**
     * 角色：super_admin / company_admin / employee / c_guest / c_user / c_partner
     */
    @Column(name = "role", nullable = false, length = 20)
    private String role;

    /**
     * 头像URL
     */
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    /**
     * 手机号
     */
    @Column(name = "phone", length = 20)
    private String phone;

    /**
     * 登录账号（企业内唯一）
     */
    @Column(name = "account", length = 100)
    private String account;

    /**
     * BCrypt加密后的密码哈希
     */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    /**
     * 是否激活
     */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;

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

    // ========== C 端字段 ==========

    /** 状态（C端：guest / registered，B端为 null） */
    @Column(length = 20)
    private String status;

    /** 来源（share / platform / partner，B端为 null） */
    @Column(length = 10)
    private String source;

    /** 来源分享ID（归因追踪） */
    @Column(name = "source_share_id")
    private UUID sourceShareId;

    /** 最后活跃时间 */
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    // ========== 常量 ==========

    public static final String STATUS_GUEST = "guest";
    public static final String STATUS_REGISTERED = "registered";
    public static final String SOURCE_ENTERPRISE = "enterprise";
    public static final String SOURCE_SHARE = "share";
    public static final String SOURCE_PLATFORM = "platform";
    public static final String SOURCE_PARTNER = "partner";

    // ========== 辅助方法 ==========

    /** 是否 C 端用户（非企业端即 C 端） */
    public boolean isCEnd() {
        return !SOURCE_ENTERPRISE.equals(source);
    }

    /** 是否游客 */
    public boolean isGuest() {
        return STATUS_GUEST.equals(status);
    }

    /**
     * 更新前自动设置更新时间
     */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
