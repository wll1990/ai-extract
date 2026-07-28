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
 * 访谈邀请码实体类
 *
 * <p>对应 interview_invite_code 表。管理员生成邀请码，不绑定 space，
 * space 由扫码登录的员工自己决定。session 在员工填完主题后动态创建。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-24
 */
@Entity
@Table(name = "interview_invite_code")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewInviteCode {

    @Id
    private UUID id;

    /** 所属企业ID */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** 邀请码（base62 8位，全局唯一） */
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
}
