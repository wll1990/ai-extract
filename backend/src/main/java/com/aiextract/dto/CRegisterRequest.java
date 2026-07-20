package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * C 端注册请求DTO — 游客原地升级为注册用户
 *
 * @author AI Extract Team
 * @since 2026-07-19
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@SuppressWarnings("PMD.ClassNamingShouldBeCamelRule")
public class CRegisterRequest {

    /**
     * 登录账号（平台全局唯一）
     */
    @NotBlank(message = "账号不能为空")
    @Size(min = 4, max = 50, message = "账号长度需在4-50位之间")
    private String account;

    /**
     * 密码
     */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码至少6位")
    private String password;

    /**
     * 昵称（选填，不填保留"访客xxxx"）
     */
    @Size(max = 50, message = "昵称最长50字")
    private String nickname;
}
