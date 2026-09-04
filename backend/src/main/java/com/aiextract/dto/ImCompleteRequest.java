package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 扫码授权完成回调请求（传输层 → 后端）。
 *
 * <p>飞书 registerApp 扫码确认后，Node 桥携此回调解锁并落库。</p>
 *
 * @author AI Extract Team
 * @since 2026-08-15
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImCompleteRequest {

    /** 注册令牌 */
    @NotBlank(message = "注册令牌不能为空")
    private String registerToken;

    /** 平台应用 ID（飞书 client_id / app_id） */
    @NotBlank(message = "应用ID不能为空")
    private String appId;

    /** 平台应用密钥（飞书 client_secret / app_secret） */
    @NotBlank(message = "应用密钥不能为空")
    private String appSecret;
}
