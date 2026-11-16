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
 * B 端用户与 IM 身份的绑定。
 *
 * <p>一个用户在同一渠道只保留一条；推送时用 bot_id + external_user_id（飞书另带 chat_id）定位。</p>
 */
@Entity
@Table(name = "im_user_binding")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImUserBinding {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** feishu / wecom / dingtalk */
    @Column(name = "channel_type", nullable = false, length = 20)
    private String channelType;

    @Column(name = "bot_id", nullable = false, length = 128)
    private String botId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 飞书 open_id / 企微 userid / 钉钉 staffId */
    @Column(name = "external_user_id", nullable = false, length = 128)
    private String externalUserId;

    /** 飞书单聊 chat_id；企微/钉钉可为空（按 userid 发送） */
    @Column(name = "chat_id", length = 128)
    private String chatId;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;
}
