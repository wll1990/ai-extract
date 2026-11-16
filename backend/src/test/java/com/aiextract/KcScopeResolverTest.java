package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.model.Skill;
import com.aiextract.service.KcScopeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KcScopeResolverTest {

    @Mock private CompanyScopeService companyScopeService;

    private KcScopeResolver resolver;
    private final Skill skill = Skill.builder().id(UUID.randomUUID()).build();

    @BeforeEach
    void setUp() {
        resolver = new KcScopeResolver(companyScopeService);
    }

    @Test
    void sameCompanyUsesPrivateKnowledge() {
        UUID companyId = UUID.randomUUID();
        when(companyScopeService.resolveSkillCompanyId(skill)).thenReturn(companyId);

        KcScopeResolver.KcScope scope = resolver.resolve(skill, companyId);

        assertEquals(companyId, scope.companyId());
        assertEquals(companyId, scope.skillCompanyId());
        assertFalse(scope.external());
    }

    @Test
    void crossCompanyOnlyUsesPublicKnowledge() {
        UUID owner = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        when(companyScopeService.resolveSkillCompanyId(skill)).thenReturn(owner);

        KcScopeResolver.KcScope scope = resolver.resolve(skill, caller);

        assertNull(scope.companyId(), "跨企业必须只检索公共库");
        assertEquals(owner, scope.skillCompanyId());
        assertTrue(scope.external());
    }

    @Test
    void skillWithoutCompanyOnlyUsesPublicKnowledge() {
        when(companyScopeService.resolveSkillCompanyId(skill)).thenReturn(null);

        KcScopeResolver.KcScope scope = resolver.resolve(skill, UUID.randomUUID());

        assertNull(scope.companyId());
        assertNull(scope.skillCompanyId());
        assertFalse(scope.external());
    }
}
