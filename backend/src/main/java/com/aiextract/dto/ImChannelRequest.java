package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * IM渠道请求DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImChannelRequest {

    /**
     * 渠道类型（必填）
     */
    @NotBlank(message = "渠道类型不能为空")
    private String channelType;

    /**
     * 是否启用
     */
    private Boolean enabled;

    /**
     * 渠道配置（appId/appSecret/webhookUrl等）
     */
    private ImChannelConfig config;

    /**
     * 关联Skill ID列表
     */
    private List<String> linkedSkills;

    /**
     * 渠道配置内部类
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImChannelConfig {

        /** 应用ID */
        private String appId;

        /** 应用密钥 */
        private String appSecret;

        /** Webhook回调地址 */
        private String webhookUrl;
    }
}
