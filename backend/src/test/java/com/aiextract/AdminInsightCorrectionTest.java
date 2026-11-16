package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.controller.AdminInsightController;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Skill;
import com.aiextract.repository.*;
import com.aiextract.service.AdminGrainService;
import com.aiextract.service.AdminInsightService;
import com.aiextract.service.DashScopeEmbeddingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** K11：回答矫正必须校验分身与颗粒归属，不能跨企业衰减权重。 */
@ExtendWith(MockitoExtension.class)
class AdminInsightCorrectionTest {

    @Mock private ConversationStatsRepository conversationStatsRepository;
    @Mock private FeedbackLogRepository feedbackLogRepository;
    @Mock private GrainRetrieveLogRepository grainRetrieveLogRepository;
    @Mock private KnowledgeGapRepository knowledgeGapRepository;
    @Mock private ExperienceGrainRepository grainRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private AdminInsightService adminInsightService;
    @Mock private AutoInsightRepository autoInsightRepository;
    @Mock private CandidateGrainRepository candidateGrainRepository;
    @Mock private DashScopeEmbeddingService embeddingService;
    @Mock private CompanyScopeService companyScopeService;
    @Mock private AdminGrainService adminGrainService;
    @Mock private AnswerCorrectionRepository correctionRepository;
    @Mock private AdminAuditLogRepository auditLogRepository;

    @InjectMocks private AdminInsightController controller;

    private final UUID skillId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();
    private final UUID grainId = UUID.randomUUID();

    @Test
    void rejectsSkillOfAnotherCompany() {
        doThrow(new BusinessException(403, "无权访问其他企业的分身"))
                .when(companyScopeService).assertSkillOwnership(skillId);

        Map<String, Object> body = Map.of(
                "skillId", skillId.toString(),
                "originalQuery", "q",
                "correctedResponse", "修正",
                "grainIds", List.of(grainId.toString()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.submitCorrection(body));
        assertEquals(403, ex.getErrorCode());
        verify(correctionRepository, never()).save(any());
        verify(grainRepository, never()).findById(any());
    }

    @Test
    void rejectsGrainFromAnotherSkill() {
        when(skillRepository.findById(skillId)).thenReturn(Optional.of(
                Skill.builder().id(skillId).spaceId(spaceId).build()));
        when(grainRepository.findById(grainId)).thenReturn(Optional.of(
                ExperienceGrain.builder().id(grainId).spaceId(UUID.randomUUID()).weight(1.0).build()));

        Map<String, Object> body = Map.of(
                "skillId", skillId.toString(),
                "originalQuery", "q",
                "correctedResponse", "修正",
                "grainIds", List.of(grainId.toString()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.submitCorrection(body));
        assertEquals(403, ex.getErrorCode());
        verify(correctionRepository, never()).save(any());
    }

    @Test
    void decaysWeightForOwnedGrain() {
        ExperienceGrain grain = ExperienceGrain.builder()
                .id(grainId).spaceId(spaceId).weight(1.0).build();
        when(skillRepository.findById(skillId)).thenReturn(Optional.of(
                Skill.builder().id(skillId).spaceId(spaceId).build()));
        when(grainRepository.findById(grainId)).thenReturn(Optional.of(grain));
        when(adminInsightService.applyCorrection(any(), anyList())).thenAnswer(inv -> {
            List<ExperienceGrain> grains = inv.getArgument(1);
            grains.forEach(g -> g.setWeight(0.7));
            return grains.size();
        });

        Map<String, Object> body = Map.of(
                "skillId", skillId.toString(),
                "originalQuery", "q",
                "correctedResponse", "修正",
                "grainIds", List.of(grainId.toString()));

        Map<String, Object> result = controller.submitCorrection(body).getData();
        assertEquals(true, result.get("corrected"));
        assertEquals(1, result.get("decayedGrains"));
        assertEquals(0.7, grain.getWeight(), 0.0001);
        verify(adminInsightService).applyCorrection(any(), anyList());
    }
}
