package com.aiextract.model;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
 * IM渠道实体类
 *
 * <p>对应 im_channel 表，管理企业接入的IM平台配置，
 * 包括飞书、企业微信、微信和钉钉。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "im_channel")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImChannel {

    /**
     * 渠道唯一标识
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 所属企业ID
     */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /**
     * 渠道类型：feishu / wecom / wechat / dingtalk
     */
    @Column(name = "channel_type", nullable = false, length = 20)
    private String channelType;

    /**
     * 是否启用
     */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    /**
     * 渠道配置（JSON，含appId/appSecret/webhookUrl等）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "JSONB")
    private String config;

    /**
     * 关联的Skill ID列表（JSON数组）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "linked_skills", columnDefinition = "JSONB")
    private String linkedSkills;

    /**
     * 平台 bot 标识（飞书 app_id / 钉钉 appKey / 企微 corpId:agentId），全局唯一路由键
     */
    @Column(name = "bot_id", length = 128)
    private String botId;

    /**
     * 绑定的分身ID（1:1），未发布前为 null
     */
    @Column(name = "skill_id")
    private UUID skillId;

    /**
     * 生命周期：draft | publishing | published | disabled
     */
    @Column(name = "status", length = 20)
    private String status;

    /**
     * 加密后的平台密钥（appSecret 等机密，AES-GCM 密文 JSON）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "secrets", columnDefinition = "JSONB")
    private String secrets;

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
