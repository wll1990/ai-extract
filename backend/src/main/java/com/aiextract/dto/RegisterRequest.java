package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 注册请求DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    /**
     * 企业ID
     */
    @NotBlank(message = "企业ID不能为空")
    private String companyId;

    /**
     * 用户姓名
     */
    @NotBlank(message = "姓名不能为空")
    private String name;

    /**
     * 登录账号（企业内唯一）
     */
    @NotBlank(message = "账号不能为空")
    private String account;

    /**
     * 密码
     */
    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 角色：super_admin / employee
     */
    @NotBlank(message = "角色不能为空")
    private String role;
}
