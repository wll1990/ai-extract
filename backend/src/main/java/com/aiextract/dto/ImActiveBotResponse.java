package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 传输层开机对账需连接的 bot 列表项。
 *
 * <p>仅经 HMAC 签名的内部通道返回，含解密后的 appSecret（用于建立 WebSocket 连接）
 * 与分身开场白/推荐问题（用于 botAdded 时主动引导）。</p>
 *
 * @author AI Extract Team
 * @since 2026-08-15
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImActiveBotResponse {

    /** 平台 bot 标识 */
    private String botId;

    /** 平台应用 ID */
    private String appId;

    /** 平台应用密钥（已解密） */
    private String appSecret;

    /** 绑定的分身 ID */
    private String skillId;

    /** 分身开场白 */
    private String openingMessage;

    /** 推荐问题列表 */
    private List<String> recommendedQuestions;
}
