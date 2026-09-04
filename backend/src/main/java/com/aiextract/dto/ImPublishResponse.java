package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 发布分身到 IM 渠道的扫码授权响应。
 *
 * @author AI Extract Team
 * @since 2026-08-15
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImPublishResponse {

    /** 注册令牌（用于轮询发布状态） */
    private String registerToken;

    /** 扫码授权链接 / 二维码内容 */
    private String qrUrl;

    /** 链接有效期（秒） */
    private Long expireIn;
}
