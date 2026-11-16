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

    /** 已接入传输层的渠道；wechat（个人微信）无官方机器人协议，不在其中 */
    public static final java.util.Set<String> SUPPORTED_CHANNELS =
            java.util.Set.of(CHANNEL_FEISHU, CHANNEL_WECOM, CHANNEL_DINGTALK);

    public static boolean isSupportedChannel(String channelType) {
        return channelType != null && SUPPORTED_CHANNELS.contains(channelType.trim().toLowerCase());
    }

    /** 凭据发布（填 App 凭据）而非扫码发布的渠道 */
    public static boolean isCredentialChannel(String channelType) {
        return CHANNEL_WECOM.equals(channelType) || CHANNEL_DINGTALK.equals(channelType);
    }

    public static String channelLabel(String channelType) {
        if (CHANNEL_WECOM.equals(channelType)) {
            return "企业微信";
        }
        if (CHANNEL_DINGTALK.equals(channelType)) {
            return "钉钉";
        }
        return "飞书";
    }

    // ── im_channel 生命周期状态 ──
    public static final String STATUS_DRAFT = "draft";
    /** 扫码授权中：已发起发布，等待桥回调 */
    public static final String STATUS_PUBLISHING = "publishing";
    public static final String STATUS_PUBLISHED = "published";
    /** 桥连接失败：凭据已保存，可在管理端重连 */
    public static final String STATUS_FAILED = "failed";
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

    // ── 合成 userId 前缀（IM 用户无 user 表记录，用确定性 UUID 做会话主键） ──
    // 飞书前缀保持历史值：改动会让已有会话失联
    public static final String SYNTHETIC_USER_PREFIX = "feishu-user:";
    public static final String SYNTHETIC_CHAT_PREFIX = "feishu-chat:";
    public static final String SYNTHETIC_DOC_PREFIX = "feishu-doc:";  // 文档评论：按 文档+评论人 记忆

    /** 按渠道隔离会话：同一员工在飞书/企微/钉钉各自独立记忆 */
    public static String userPrefix(String channelType) {
        if (CHANNEL_WECOM.equals(channelType)) {
            return "wecom-user:";
        }
        if (CHANNEL_DINGTALK.equals(channelType)) {
            return "dingtalk-user:";
        }
        return SYNTHETIC_USER_PREFIX;
    }

    public static String chatPrefix(String channelType) {
        if (CHANNEL_WECOM.equals(channelType)) {
            return "wecom-chat:";
        }
        if (CHANNEL_DINGTALK.equals(channelType)) {
            return "dingtalk-chat:";
        }
        return SYNTHETIC_CHAT_PREFIX;
    }

    public static String docPrefix(String channelType) {
        if (CHANNEL_WECOM.equals(channelType)) {
            return "wecom-doc:";
        }
        if (CHANNEL_DINGTALK.equals(channelType)) {
            return "dingtalk-doc:";
        }
        return SYNTHETIC_DOC_PREFIX;
    }
}
