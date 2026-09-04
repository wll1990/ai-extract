package com.aiextract.common;

/**
 * IM 渠道接入常量 — 渠道类型 / 生命周期状态 / 会话类型 / 消息类型 / 分身类型。
 *
 * <p>统一替代散落的魔法字符串，保证后端与传输层契约一致。</p>
 *
 * @author AI Extract Team
 * @since 2026-08-16
 */
public final class ImConstants {

    private ImConstants() {}

    // ── 渠道类型（im_channel.channel_type） ──
    public static final String CHANNEL_FEISHU = "feishu";
    public static final String CHANNEL_DINGTALK = "dingtalk";
    public static final String CHANNEL_WECOM = "wecom";
    public static final String CHANNEL_WECHAT = "wechat";

    // ── im_channel 生命周期状态 ──
    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHING = "publishing";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_DISABLED = "disabled";

    // ── 会话类型 ──
    public static final String CONV_P2P = "p2p";
    public static final String CONV_GROUP = "group";
    public static final String CONV_DOC = "doc";        // 云文档评论（文档问答/复盘）

    // ── 消息类型 ──
    public static final String MSG_TYPE_TEXT = "text";

    // ── 分身类型（skill.type） ──
    public static final String SKILL_TYPE_ORGANIZATION = "organization";

    // ── 分身状态（skill.status，与 im_channel.status 是不同域） ──
    public static final String SKILL_STATUS_PUBLISHED = "published";

    // ── 合成 userId 前缀（飞书用户无 user 表记录，用确定性 UUID 做会话主键） ──
    public static final String SYNTHETIC_USER_PREFIX = "feishu-user:";
    public static final String SYNTHETIC_CHAT_PREFIX = "feishu-chat:";
    public static final String SYNTHETIC_DOC_PREFIX = "feishu-doc:";  // 文档评论：按 文档+评论人 记忆
}
