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
 * 分身分享链接实体类
 *
 * <p>对应 skill_share 表。每个分享是一条可独立启停的短码，
 * 对外可聊 = skill.status='published' 且 enabled=true。
 * channel 字段为二期多渠道发码/转化统计预留。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-19
 */
@Entity
@Table(name = "skill_share")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillShare {

    /** 对外分享渠道 */
    public static final String CHANNEL_PUBLIC = "public";

    /** 对内分享渠道 */
    public static final String CHANNEL_INTERNAL = "internal";

    /**
     * 分享唯一标识
     */
    @Id
    private UUID id;

    /**
     * 所属AI分身ID
     */
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    /**
     * 分身所属企业ID（建码时经 skill→space→user 解析冗余，归因用）。
     * C 端分身分享时为 null。
     */
    @Column(name = "company_id")
    private UUID companyId;

    /**
     * 短码，URL 形如 /s/{share_code}，base62 随机 10 位，全局唯一
     */
    @Column(name = "share_code", nullable = false, length = 16)
    private String shareCode;

    /**
     * 渠道标识，本期恒为 default
     */
    @Column(nullable = false, length = 50)
    private String channel;

    /**
     * 共享开关：关闭后分享链接立即失效
     */
    @Column(nullable = false)
    private Boolean enabled;

    /**
     * 创建人（管理员 userId）
     */
    @Column(name = "created_by")
    private UUID createdBy;

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
