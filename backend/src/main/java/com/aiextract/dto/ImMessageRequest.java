package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * IM 入站消息 — 平台无关的统一契约（传输层 → 后端）。
 *
 * <p>由各 IM 传输层（Node 飞书桥 / 后续钉钉 Java SDK / 企微 webhook）归一化后投递。
 * 后端据此做去重、路由到指定分身并流式返回回复。</p>
 *
 * @author AI Extract Team
 * @since 2026-08-15
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImMessageRequest {

    /** 渠道类型：feishu / dingtalk / wecom / wechat */
    private String channelType;

    /** 平台 bot 标识（飞书 app_id / 钉钉 appKey / 企微 corpId:agentId），路由键 */
    private String botId;

    /** 平台消息 ID，去重键（必填） */
    private String messageId;

    /** 会话信息 */
    private Conversation conversation;

    /** 发送者信息 */
    private Sender sender;

    /** 消息内容 */
    private Message message;

    /** 平台时间戳（秒） */
    private Long timestamp;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Conversation {
        /** 平台会话 ID（单聊=用户，群聊=群） */
        private String id;

        /** 会话类型：p2p | group */
        private String type;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sender {
        /** 平台用户 ID（open_id / user_id / union_id） */
        private String id;

        /** 展示名（可选，仅日志/备注用） */
        private String displayName;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        /** 消息类型：text（首期）；image/file/audio 后续 */
        private String type;

        /** 已提取的纯文本（type=text 时） */
        private String text;

        /** @ 提及的用户 ID 列表（可选） */
        private List<String> mentions;
    }
}
