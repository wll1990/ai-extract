package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.GrainCorrectionSuggestion;
import com.aiextract.model.Skill;
import com.aiextract.repository.AdminAuditLogRepository;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.GrainCorrectionSuggestionRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.service.AdminGrainService;
import com.aiextract.service.GrainCorrectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 颗粒纠错：员工提交进待审队列，管理员通过后才写颗粒（GR-06）。
 */
@ExtendWith(MockitoExtension.class)
class GrainCorrectionServiceTest {

    @Mock private GrainCorrectionSuggestionRepository repository;
    @Mock private ExperienceGrainRepository grainRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private CompanyScopeService companyScopeService;
    @Mock private AdminGrainService adminGrainService;
    @Mock private AdminAuditLogRepository auditLogRepository;

    private GrainCorrectionService service;
    private final UUID companyId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID skillId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();
    private final UUID grainId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GrainCorrectionService(repository, grainRepository, skillRepository,
                companyScopeService, adminGrainService, auditLogRepository, new ObjectMapper());
        TokenContext.set(userId, companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    private Skill skill() {
        return Skill.builder().id(skillId).companyId(companyId).type("individual")
                .spaceId(spaceId).build();
    }

    private ExperienceGrain grain() {
        return ExperienceGrain.builder().id(grainId).spaceId(spaceId)
                .expertThought("旧思路").standardScript("旧话术").build();
    }

    @Test
    void submitRequiresReason() {
        when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill()));
        when(grainRepository.findById(grainId)).thenReturn(Optional.of(grain()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submit(skillId, grainId, Map.of("reason", "  ")));
        assertEquals(400, ex.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    void submitRejectsGrainFromAnotherSkill() {
        when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill()));
        when(grainRepository.findById(grainId)).thenReturn(Optional.of(
                ExperienceGrain.builder().id(grainId).spaceId(UUID.randomUUID()).build()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submit(skillId, grainId, Map.of("reason", "话术过期")));
        assertEquals(404, ex.getErrorCode());
    }

    @Test
    void submitDuplicatesAreRejected() {
        when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill()));
        when(grainRepository.findById(grainId)).thenReturn(Optional.of(grain()));
        when(repository.findFirstByGrainIdAndSuggestedByAndStatusOrderByCreatedAtDesc(
                grainId, userId, GrainCorrectionSuggestion.STATUS_PENDING))
                .thenReturn(Optional.of(GrainCorrectionSuggestion.builder().id(UUID.randomUUID()).build()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submit(skillId, grainId, Map.of("reason", "话术过期")));
        assertEquals(409, ex.getErrorCode());
    }

    @Test
    void submitStoresPendingSuggestion() {
        when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill()));
        when(grainRepository.findById(grainId)).thenReturn(Optional.of(grain()));

        GrainCorrectionSuggestion s = service.submit(skillId, grainId,
                Map.of("reason", "话术过期", "standardScript", "新话术"));

        assertEquals(GrainCorrectionSuggestion.STATUS_PENDING, s.getStatus());
        assertEquals(companyId, s.getCompanyId());
        assertEquals(userId, s.getSuggestedBy());
        assertEquals("新话术", s.getStandardScript());
        verify(repository).save(s);
    }

    @Test
    void approveAppliesFieldsToGrain() {
        GrainCorrectionSuggestion s = GrainCorrectionSuggestion.builder()
                .id(UUID.randomUUID()).grainId(grainId).skillId(skillId).companyId(companyId)
                .suggestedBy(userId).reason("话术过期").standardScript("新话术")
                .status(GrainCorrectionSuggestion.STATUS_PENDING).build();
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));
        ExperienceGrain grain = grain();
        when(grainRepository.findById(grainId)).thenReturn(Optional.of(grain));

        service.applyApprove(s.getId(), "确认更新");

        assertEquals("新话术", grain.getStandardScript());
        assertEquals(GrainCorrectionSuggestion.STATUS_APPROVED, s.getStatus());
        verify(grainRepository).save(grain);
    }

    @Test
    void rejectDoesNotTouchGrain() {
        GrainCorrectionSuggestion s = GrainCorrectionSuggestion.builder()
                .id(UUID.randomUUID()).grainId(grainId).skillId(skillId).companyId(companyId)
                .suggestedBy(userId).reason("话术过期")
                .status(GrainCorrectionSuggestion.STATUS_PENDING).build();
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));

        service.reject(s.getId(), "不采纳");

        assertEquals(GrainCorrectionSuggestion.STATUS_REJECTED, s.getStatus());
        verify(grainRepository, never()).save(any());
    }
}
