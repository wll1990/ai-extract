package com.aiextract;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.RolePermissions;
import com.aiextract.config.TokenContext;
import com.aiextract.controller.NavConfigController;
import com.aiextract.model.User;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.NavBadgeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 导航域过滤 — 同路径不同角色（上传素材 / 素材管理）与域归属。
 */
@ExtendWith(MockitoExtension.class)
class NavConfigControllerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private NavBadgeService navBadgeService;

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();

    private NavConfigController controller() {
        return new NavConfigController(userRepository, navBadgeService);
    }

    private void loginAs(String role) {
        var perms = RolePermissions.getPermissions(role);
        var auth = new UsernamePasswordAuthenticationToken("u", null,
                perms.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList()));
        SecurityContextHolder.getContext().setAuthentication(auth);
        TokenContext.set(userId, companyId);
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(User.builder().id(userId).companyId(companyId).role(role).build()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TokenContext.clear();
    }

    private List<Map<String, String>> nav() {
        ApiResponse<List<Map<String, String>>> res = controller().nav();
        return res.getData();
    }

    private Map<String, String> item(List<Map<String, String>> items, String label) {
        return items.stream().filter(i -> label.equals(i.get("label"))).findFirst().orElse(null);
    }

    @Test
    void companyAdminSeesMaterialManagementInProduction() {
        loginAs(RolePermissions.COMPANY_ADMIN);

        List<Map<String, String>> items = nav();

        Map<String, String> manage = item(items, "素材管理");
        assertEquals("production", manage.get("domain"));
        assertEquals("/admin/materials", manage.get("path"));
        assertEquals(null, item(items, "上传素材"));
        assertFalse(items.stream().anyMatch(i -> "platform".equals(i.get("domain"))));
    }

    @Test
    void employeeSeesUploadEntryInDailyAndNoProductionDomain() {
        loginAs(RolePermissions.EMPLOYEE);

        List<Map<String, String>> items = nav();

        Map<String, String> upload = item(items, "上传素材");
        assertEquals("daily", upload.get("domain"));
        assertEquals("/admin/materials", upload.get("path"));
        assertEquals(null, item(items, "素材管理"));
        assertFalse(items.stream().anyMatch(i -> "production".equals(i.get("domain"))));
    }

    @Test
    void superAdminSeesPlatformDomain() {
        loginAs(RolePermissions.SUPER_ADMIN);

        List<Map<String, String>> items = nav();

        assertTrue(items.stream().anyMatch(i -> "platform".equals(i.get("domain"))));
        assertEquals("platform", item(items, "企业合作").get("domain"));
    }
}
