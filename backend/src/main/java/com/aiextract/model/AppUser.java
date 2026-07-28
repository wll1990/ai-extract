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
 * C 端用户实体类
 *
 * <p>对应 app_user 表。平台级用户体系，与企业多租户 user 表完全独立：
 * 游客（status=guest，无账号密码）通过分享链接静默创建，
 * 注册时原地升级为 registered（UUID 不变，会话历史自动继承）。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-19
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {

    /** 游客状态 */
    public static final String STATUS_GUEST = "guest";

    /** 已注册状态 */
    public static final String STATUS_REGISTERED = "registered";

    /** 来源：从分享链接来的（/s/{code}） */
    public static final String SOURCE_SHARE = "share";

    /** 来源：自己到 platform 注册的 */
    public static final String SOURCE_PLATFORM = "platform";

    /** 来源：合作方嵌入自动创建的 */
    public static final String SOURCE_PARTNER = "partner";

    /**
     * 用户唯一标识（= 访客身份本体，注册后不变）
     */
    @Id
    private UUID id;

    /**
     * 昵称，游客自动生成"访客xxxx"，注册时可改
     */
    @Column(nullable = false, length = 50)
    private String nickname;

    /**
     * 登录账号，游客为 NULL，注册后平台全局唯一
     */
    @Column(length = 100)
    private String account;

    /**
     * BCrypt 密码哈希，游客为 NULL
     */
    @Column(name = "password_hash")
    private String passwordHash;

    /**
     * 状态: guest=游客 / registered=已注册
     */
    @Column(nullable = false, length = 20)
    private String status;

    /**
     * 用户来源。
     * share=分享链接来的 / platform=平台注册 / partner=合作方嵌入
     */
    @Column(length = 10)
    private String source;

    /**
     * 所属企业 UUID。
     * 仅 source='partner' 时有值 = PartnerApp.app_id（即 Company UUID）。
     * C 端独立用户（share/platform）为 null。
     */
    @Column(name = "company_id")
    private UUID companyId;

    /**
     * 来源分享ID（skill_share.id），转化归因
     */
    @Column(name = "source_share_id")
    private UUID sourceShareId;

    /**
     * 最后活跃时间（进入分享页/续期时刷新）
     */
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    /**
     * 创建时间
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
