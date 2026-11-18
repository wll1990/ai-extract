package com.aiextract.service.skill;

import com.aiextract.config.Permission;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AgentSkill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 技能归属：跨租户 404、平台级写入仅平台管理员。
 */
class AgentSkillScopeGuardTest {

    private static final UUID COMPANY_A = UUID.randomUUID();
    private static final UUID COMPANY_B = UUID.randomUUID();

    private final AgentSkillScopeGuard guard = new AgentSkillScopeGuard();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        TokenContext.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TokenContext.clear();
    }

    @Test
    void tenantSkillReadableOnlyInSameCompany() {
        TokenContext.set(UUID.randomUUID(), COMPANY_A);
        AgentSkill skill = tenantSkill(COMPANY_A);

        guard.requireReadable(skill);

        TokenContext.set(UUID.randomUUID(), COMPANY_B);
        BusinessException error = assertThrows(BusinessException.class,
                () -> guard.requireReadable(skill));
        assertEquals(404, error.getErrorCode());
    }

    @Test
    void platformSkillIsReadOnlyForCompanyAdmin() {
        TokenContext.set(UUID.randomUUID(), COMPANY_A);
        authenticate(Permission.SKILL_MANAGE);
        AgentSkill skill = platformSkill();

        guard.requireReadable(skill);
        BusinessException error = assertThrows(BusinessException.class,
                () -> guard.requireWritable(skill));
        assertEquals(403, error.getErrorCode());
    }

    @Test
    void platformAdminCanWritePlatformSkill() {
        TokenContext.set(UUID.randomUUID(), COMPANY_A);
        authenticate(Permission.COMPANY_MANAGE);

        guard.requireWritable(platformSkill());
        guard.requirePlatformScope();
    }

    @Test
    void companyAdminCannotUsePlatformScope() {
        TokenContext.set(UUID.randomUUID(), COMPANY_A);
        authenticate(Permission.SKILL_MANAGE);

        BusinessException error = assertThrows(BusinessException.class, guard::requirePlatformScope);
        assertEquals(403, error.getErrorCode());
    }

    private static void authenticate(String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null,
                        List.of(new SimpleGrantedAuthority(authority))));
    }

    private static AgentSkill tenantSkill(UUID companyId) {
        return AgentSkill.builder()
                .id(UUID.randomUUID())
                .skillKey("tenant-skill")
                .companyId(companyId)
                .status(AgentSkill.STATUS_PUBLISHED)
                .source(AgentSkill.SOURCE_TENANT)
                .build();
    }

    private static AgentSkill platformSkill() {
        return AgentSkill.builder()
                .id(UUID.randomUUID())
                .skillKey("platform-skill")
                .companyId(null)
                .status(AgentSkill.STATUS_PUBLISHED)
                .source(AgentSkill.SOURCE_PLATFORM)
                .build();
    }
}
