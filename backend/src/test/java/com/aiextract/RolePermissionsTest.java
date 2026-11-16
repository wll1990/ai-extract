package com.aiextract;

import com.aiextract.config.Permission;
import com.aiextract.config.RolePermissions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限映射单元测试 — 验证 KNOWLEDGE_MANAGE 只授予 super_admin + company_admin，不泄漏给 employee。
 */
class RolePermissionsTest {

    @Test
    void knowledgeManage_onAdminRoles() {
        assertTrue(RolePermissions.getPermissions(RolePermissions.SUPER_ADMIN)
                .contains(Permission.KNOWLEDGE_MANAGE));
        assertTrue(RolePermissions.getPermissions(RolePermissions.COMPANY_ADMIN)
                .contains(Permission.KNOWLEDGE_MANAGE));
    }

    @Test
    void knowledgeManage_notOnEmployeeOrCEnd() {
        assertFalse(RolePermissions.getPermissions(RolePermissions.EMPLOYEE)
                .contains(Permission.KNOWLEDGE_MANAGE));
        assertFalse(RolePermissions.getPermissions(RolePermissions.C_USER)
                .contains(Permission.KNOWLEDGE_MANAGE));
        assertFalse(RolePermissions.getPermissions(RolePermissions.C_GUEST)
                .contains(Permission.KNOWLEDGE_MANAGE));
    }

    @Test
    void aiModelManage_onAdminRolesOnly() {
        assertTrue(RolePermissions.getPermissions(RolePermissions.SUPER_ADMIN)
                .contains(Permission.AI_MODEL_MANAGE));
        assertTrue(RolePermissions.getPermissions(RolePermissions.COMPANY_ADMIN)
                .contains(Permission.AI_MODEL_MANAGE));
        assertFalse(RolePermissions.getPermissions(RolePermissions.EMPLOYEE)
                .contains(Permission.AI_MODEL_MANAGE));
        assertFalse(RolePermissions.getPermissions(RolePermissions.C_USER)
                .contains(Permission.AI_MODEL_MANAGE));
    }
}
