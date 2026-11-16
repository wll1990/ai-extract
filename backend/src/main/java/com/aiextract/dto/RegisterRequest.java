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
     * 企业ID — 管理员创建用户时必填；匿名注册由 inviteToken 解析，忽略此字段。
     */
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

    /**
     * 注册邀请令牌（公开分享页/访谈邀请页下发）。
     * 匿名注册时必填，服务端据此确定企业归属，忽略请求体 companyId。
     */
    private String inviteToken;
}
