package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 发布分身到 IM 渠道请求（管理员发起）。
 *
 * @author AI Extract Team
 * @since 2026-08-15
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImPublishRequest {

    /** 要发布的分身 ID（必填） */
    @NotBlank(message = "分身ID不能为空")
    private String skillId;

    /** 机器人名称（可选，缺省取分身 displayName） */
    private String name;

    /** 机器人描述（可选，缺省取分身简介） */
    private String description;

    /** 机器人头像 URL（可选，需公网 URL） */
    private String avatar;

    /**
     * 应用凭据（企微/钉钉必填，飞书扫码发布忽略）。
     * <p>企微：corpId / agentId / secret / token / encodingAesKey<br>
     * 钉钉：appKey / appSecret（robotCode 可选）</p>
     */
    private java.util.Map<String, String> credentials;
}
