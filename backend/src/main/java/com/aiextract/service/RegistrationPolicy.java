package com.aiextract.service;

import com.aiextract.config.Permission;
import com.aiextract.config.RolePermissions;
import com.aiextract.exception.BusinessException;

import java.util.Set;
import java.util.UUID;

/**
 * 注册角色授权策略。
 *
 * <p>注册接口是公开的，不能信任请求体里的 role：匿名只能注册员工；
 * 管理员创建 company_admin 需要 USER_MANAGE，且只能在本企业内创建；
 * 只有 super_admin 能创建 super_admin。</p>
 */
public final class RegistrationPolicy {

    private RegistrationPolicy() {
    }

    public static void assertAssignable(String role,
                                        String callerRole,
                                        UUID callerCompanyId,
                                        UUID targetCompanyId,
                                        Set<String> callerPermissions,
                                        boolean inviteVerified) {
        if (!RolePermissions.REGISTRABLE_ROLES.contains(role)
                && !RolePermissions.SUPER_ADMIN.equals(role)) {
            throw new BusinessException(400, "不支持的角色");
        }

        // 匿名注册（公开分享页/访谈邀请页）必须携带服务端签发的邀请令牌，且只能创建员工
        if (callerRole == null) {
            if (!inviteVerified) {
                throw new BusinessException(403, "匿名注册需要有效的邀请令牌");
            }
            if (!RolePermissions.EMPLOYEE.equals(role)) {
                throw new BusinessException(403, "匿名注册只能创建员工账号");
            }
            return;
        }

        boolean superAdmin = RolePermissions.SUPER_ADMIN.equals(callerRole);
        if (RolePermissions.SUPER_ADMIN.equals(role) && !superAdmin) {
            throw new BusinessException(403, "无权限创建超级管理员");
        }
        if (superAdmin) {
            return;
        }
        // 已登录用户建号必须有用户管理权限：员工不能绕过邀请码流程批量造号
        if (callerPermissions == null || !callerPermissions.contains(Permission.USER_MANAGE)) {
            throw new BusinessException(403, "无权限创建用户");
        }
        if (callerCompanyId == null || !callerCompanyId.equals(targetCompanyId)) {
            throw new BusinessException(403, "只能在本企业创建用户");
        }
        if (RolePermissions.COMPANY_ADMIN.equals(role)
                && (callerPermissions == null
                    || !callerPermissions.contains(Permission.USER_MANAGE))) {
            throw new BusinessException(403, "无权限创建企业管理员");
        }
    }
}
