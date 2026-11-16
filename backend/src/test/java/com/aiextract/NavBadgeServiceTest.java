package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.repository.ReviewCoachRequestRepository;
import com.aiextract.repository.SkillMaterialRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.service.NavBadgeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NavBadgeServiceTest {

    @Mock private CompanyScopeService companyScopeService;
    @Mock private SkillRepository skillRepository;
    @Mock private SkillMaterialRepository materialRepository;
    @Mock private ReviewCoachRequestRepository coachRequestRepository;

    private NavBadgeService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new NavBadgeService(companyScopeService, skillRepository, materialRepository, coachRequestRepository);
        TokenContext.set(userId, companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void aggregatesProductionAndCoachCounts() {
        UUID spaceId = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(false);
        when(companyScopeService.getSpaceIds(companyId)).thenReturn(Set.of(spaceId));
        when(skillRepository.countByStatusInAndSpaceIdIn(anyList(), eq(List.of(spaceId)))).thenReturn(2L);
        when(skillRepository.findIdsBySpaceIdIn(List.of(spaceId))).thenReturn(List.of(skillId));
        when(materialRepository.countByStatusInAndSkillIdIn(anyList(), eq(List.of(skillId)))).thenReturn(3L);
        when(coachRequestRepository.countByCompanyIdAndCoachUserIdAndStatus(companyId, userId, "pending"))
                .thenReturn(1L);

        Map<String, Object> badges = service.getBadges();

        @SuppressWarnings("unchecked")
        Map<String, Long> domains = (Map<String, Long>) badges.get("domains");
        @SuppressWarnings("unchecked")
        Map<String, Long> items = (Map<String, Long>) badges.get("items");
        assertEquals(5L, domains.get("production"));
        assertEquals(1L, domains.get("sales"));
        assertEquals(2L, items.get("/admin/skills"));
        assertEquals(3L, items.get("/admin/materials"));
        assertEquals(1L, items.get("/sales/coach/ledger"));
    }

    @Test
    void returnsEmptyWhenNoSpace() {
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(false);
        when(companyScopeService.getSpaceIds(companyId)).thenReturn(Set.of());
        lenient().when(coachRequestRepository.countByCompanyIdAndCoachUserIdAndStatus(any(), any(), any()))
                .thenReturn(0L);

        Map<String, Object> badges = service.getBadges();

        @SuppressWarnings("unchecked")
        Map<String, Long> domains = (Map<String, Long>) badges.get("domains");
        assertTrue(domains.isEmpty());
    }

    @Test
    void superAdminGetsNoBadges() {
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(true);

        Map<String, Object> badges = service.getBadges();

        @SuppressWarnings("unchecked")
        Map<String, Long> domains = (Map<String, Long>) badges.get("domains");
        assertTrue(domains.isEmpty());
    }
}
