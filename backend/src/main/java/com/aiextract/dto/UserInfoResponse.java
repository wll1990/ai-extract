package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 用户信息响应DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoResponse {

    /**
     * 用户唯一标识
     */
    private String id;

    /**
     * 用户姓名
     */
    private String name;

    /**
     * 角色
     */
    private String role;

    /**
     * 头像URL
     */
    private String avatarUrl;

    /**
     * 所属企业ID
     */
    private String companyId;

    /**
     * 企业名称
     */
    private String companyName;
}
