package com.aiextract;

import com.aiextract.config.Permission;
import com.aiextract.config.RolePermissions;
import com.aiextract.exception.BusinessException;
import com.aiextract.service.RegistrationPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistrationPolicyTest {

    private final UUID companyA = UUID.randomUUID();
    private final UUID companyB = UUID.randomUUID();

    @Test
    void anonymousWithoutInviteCannotRegisterEmployee() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RegistrationPolicy.assertAssignable(
                        RolePermissions.EMPLOYEE, null, null, companyA, Set.of(), false));
        assertEquals(403, ex.getErrorCode());
    }

    @Test
    void anonymousWithInviteCanRegisterEmployee() {
        assertDoesNotThrow(() -> RegistrationPolicy.assertAssignable(
                RolePermissions.EMPLOYEE, null, null, companyA, Set.of(), true));
    }

    @Test
    void anonymousCannotRegisterCompanyAdmin() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RegistrationPolicy.assertAssignable(
                        RolePermissions.COMPANY_ADMIN, null, null, companyA, Set.of(), false));
        assertEquals(403, ex.getErrorCode());
    }

    @Test
    void anonymousCannotRegisterSuperAdmin() {
        assertThrows(BusinessException.class, () -> RegistrationPolicy.assertAssignable(
                RolePermissions.SUPER_ADMIN, null, null, companyA, Set.of(), false));
    }

    @Test
    void companyAdminCanCreateCompanyAdminInOwnCompany() {
        assertDoesNotThrow(() -> RegistrationPolicy.assertAssignable(
                RolePermissions.COMPANY_ADMIN, RolePermissions.COMPANY_ADMIN,
                companyA, companyA, Set.of(Permission.USER_MANAGE), false));
    }

    @Test
    void companyAdminCannotCreateCompanyAdminWithoutUserManage() {
        assertThrows(BusinessException.class, () -> RegistrationPolicy.assertAssignable(
                RolePermissions.COMPANY_ADMIN, RolePermissions.COMPANY_ADMIN,
                companyA, companyA, Set.of(), false));
    }

    @Test
    void employeeCannotCreateCompanyAdmin() {
        assertThrows(BusinessException.class, () -> RegistrationPolicy.assertAssignable(
                RolePermissions.COMPANY_ADMIN, RolePermissions.EMPLOYEE,
                companyA, companyA, Set.of(), false));
    }

    @Test
    void adminCannotCreateUserInAnotherCompany() {
        assertThrows(BusinessException.class, () -> RegistrationPolicy.assertAssignable(
                RolePermissions.EMPLOYEE, RolePermissions.COMPANY_ADMIN,
                companyA, companyB, Set.of(Permission.USER_MANAGE), false));
    }

    @Test
    void superAdminCanCreateSuperAdminAnywhere() {
        assertDoesNotThrow(() -> RegistrationPolicy.assertAssignable(
                RolePermissions.SUPER_ADMIN, RolePermissions.SUPER_ADMIN,
                companyA, companyB, Set.of(Permission.COMPANY_MANAGE), false));
    }

    @Test
    void employeeCannotCreateEmployeeAccounts() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> RegistrationPolicy.assertAssignable(
                        RolePermissions.EMPLOYEE, RolePermissions.EMPLOYEE,
                        companyA, companyA, Set.of(), false));
        assertEquals(403, ex.getErrorCode());
    }

    @Test
    void unknownRoleRejected() {
        assertThrows(BusinessException.class, () -> RegistrationPolicy.assertAssignable(
                "c_user", null, null, companyA, Set.of(), false));
    }
}
