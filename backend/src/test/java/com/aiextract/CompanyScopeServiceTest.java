package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.Skill;
import com.aiextract.model.Space;
import com.aiextract.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;

/** 跨租户归属校验回归：A10 / K11 的基础设施。 */
@ExtendWith(MockitoExtension.class)
class CompanyScopeServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private SpaceRepository spaceRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private ExperienceGrainRepository grainRepository;
    @Mock private KnowledgeEntryRepository knowledgeEntryRepository;
    @Mock private IndustryCaseRepository caseRepository;
    @Mock private EvaluationDatasetRepository evaluationDatasetRepository;
    @Mock private EvaluationRunRepository evaluationRunRepository;

    private CompanyScopeService service;

    private final UUID companyA = UUID.randomUUID();
    private final UUID companyB = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();
    private final UUID skillId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new CompanyScopeService(userRepository, spaceRepository, skillRepository,
                grainRepository, knowledgeEntryRepository, caseRepository,
                evaluationDatasetRepository, evaluationRunRepository);
        CompanyScopeService.clearCache();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("u", "t",
                        List.of(new SimpleGrantedAuthority("SKILL_MANAGE"))));
        TokenContext.set(userId, companyA);
        lenient().when(userRepository.findByCompanyId(companyA))
                .thenReturn(List.of(com.aiextract.model.User.builder().id(userId).companyId(companyA).build()));
        lenient().when(spaceRepository.findByUserIdIn(List.of(userId)))
                .thenReturn(List.of(Space.builder().id(spaceId).userId(userId).build()));
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
        CompanyScopeService.clearCache();
        SecurityContextHolder.clearContext();
    }

    private Skill skill(UUID companyId) {
        return Skill.builder().id(skillId).spaceId(spaceId).companyId(companyId).build();
    }

    @Test
    void otherCompanySkillIsForbidden() {
        // 空间属于当前企业，但 skill 记录指向另一企业：以空间集合为准仍放行，
        // 这里模拟“另一企业的空间”不在当前企业空间集合中
        lenient().when(spaceRepository.findByUserIdIn(List.of(userId))).thenReturn(List.of());
        lenient().when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill(companyA)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertSkillOwnership(skillId));
        assertEquals(403, ex.getErrorCode());
    }

    @Test
    void sameCompanySkillPasses() {
        lenient().when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill(companyA)));
        assertDoesNotThrow(() -> service.assertSkillOwnership(skillId));
    }

    @Test
    void organizationSkillOfOtherCompanyIsForbidden() {
        Skill org = Skill.builder().id(skillId).spaceId(null).companyId(companyB).build();
        lenient().when(skillRepository.findById(skillId)).thenReturn(Optional.of(org));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertSkillOwnership(skillId));
        assertEquals(403, ex.getErrorCode());
    }

    @Test
    void cEndNonOwnerIsForbidden() {
        TokenContext.set(userId, null);
        lenient().when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill(companyA)));
        lenient().when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(
                Space.builder().id(spaceId).userId(UUID.randomUUID()).build()));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertSkillOwnership(skillId));
        assertEquals(403, ex.getErrorCode());
    }

    @Test
    void otherCompanyKnowledgeIs404() {
        UUID id = UUID.randomUUID();
        lenient().when(knowledgeEntryRepository.findById(id)).thenReturn(Optional.of(
                com.aiextract.model.KnowledgeEntry.builder().id(id).companyId(companyB)
                        .status(com.aiextract.model.KnowledgeEntry.STATUS_ACTIVE).build()));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireKnowledgeReadable(id));
        assertEquals(404, ex.getErrorCode());
    }

    @Test
    void otherCompanyCaseIs404() {
        UUID id = UUID.randomUUID();
        lenient().when(caseRepository.findById(id)).thenReturn(Optional.of(
                com.aiextract.model.IndustryCase.builder().id(id).companyId(companyB)
                        .status(com.aiextract.model.IndustryCase.STATUS_ACTIVE).build()));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireCaseReadable(id));
        assertEquals(404, ex.getErrorCode());
    }

    @Test
    void publicPendingKnowledgeOtherTenantIs404() {
        UUID id = UUID.randomUUID();
        lenient().when(knowledgeEntryRepository.findById(id)).thenReturn(Optional.of(
                com.aiextract.model.KnowledgeEntry.builder().id(id).companyId(null)
                        .sourceCompanyId(companyB)
                        .status(com.aiextract.model.KnowledgeEntry.STATUS_PENDING).build()));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireKnowledgeReadable(id));
        assertEquals(404, ex.getErrorCode());
    }

    @Test
    void publicPendingKnowledgeSubmitterPasses() {
        UUID id = UUID.randomUUID();
        lenient().when(knowledgeEntryRepository.findById(id)).thenReturn(Optional.of(
                com.aiextract.model.KnowledgeEntry.builder().id(id).companyId(null)
                        .sourceCompanyId(companyA)
                        .status(com.aiextract.model.KnowledgeEntry.STATUS_PENDING).build()));
        assertDoesNotThrow(() -> service.requireKnowledgeReadable(id));
    }

    @Test
    void publicActiveKnowledgePasses() {
        UUID id = UUID.randomUUID();
        lenient().when(knowledgeEntryRepository.findById(id)).thenReturn(Optional.of(
                com.aiextract.model.KnowledgeEntry.builder().id(id).companyId(null)
                        .status(com.aiextract.model.KnowledgeEntry.STATUS_ACTIVE).build()));
        assertDoesNotThrow(() -> service.requireKnowledgeReadable(id));
    }

    @Test
    void publicUnpublishedDatasetIs404() {
        UUID id = UUID.randomUUID();
        lenient().when(evaluationDatasetRepository.findById(id)).thenReturn(Optional.of(
                com.aiextract.model.EvaluationDataset.builder().id(id).companyId(null)
                        .status(com.aiextract.model.EvaluationDataset.STATUS_DRAFT).build()));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertDatasetReadable(id));
        assertEquals(404, ex.getErrorCode());
    }

    @Test
    void superAdminGetSpaceIdsUsesActingCompany() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "t",
                        List.of(new SimpleGrantedAuthority("COMPANY_MANAGE"))));
        assertEquals(java.util.Set.of(spaceId), service.getSpaceIds(companyA));
    }
}
