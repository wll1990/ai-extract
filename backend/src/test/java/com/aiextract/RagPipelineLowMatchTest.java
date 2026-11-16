package com.aiextract;

import com.aiextract.model.ExperienceGrain;
import com.aiextract.repository.*;
import com.aiextract.service.ChatStreamAdapter;
import com.aiextract.service.GrainRetriever;
import com.aiextract.service.RagPipelineService;
import com.aiextract.config.DomainConfigLoader;
import com.aiextract.config.PromptLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** K5：有命中但全部低于参考阈值时，必须写 knowledge_gap。 */
@ExtendWith(MockitoExtension.class)
class RagPipelineLowMatchTest {

    @Mock private GrainRetriever grainRetriever;
    @Mock private GrainRetrieveLogRepository grainRetrieveLogRepository;
    @Mock private KnowledgeGapRepository knowledgeGapRepository;
    @Mock private ExperienceGrainRepository grainRepository;
    @Mock private DomainConfigLoader domainConfigLoader;
    @Mock private ChatStreamAdapter chatStreamAdapter;
    @Mock private PromptLoader promptLoader;
    @Mock private SkillMessageRepository skillMessageRepository;

    @InjectMocks private RagPipelineService service;

    @Test
    void allHitsBelowReferenceThresholdWritesGap() {
        UUID spaceId = UUID.randomUUID();
        UUID grainId = UUID.randomUUID();
        ExperienceGrain grain = ExperienceGrain.builder()
                .id(grainId).spaceId(spaceId).sceneTag("价格异议")
                .sceneDescription("客户嫌贵").expertThought("先拆分成本")
                .standardScript("我们先看三年总成本").status("active").weight(1.0)
                .build();
        // 0.10 < ref 0.30，但高于测试环境的 minSimilarity=0
        when(grainRetriever.retrieveWithScores(anyString(), eq(spaceId), anyInt(), eq(false)))
                .thenReturn(List.of(new GrainRetriever.GrainResult(grain, 0.10, 0.10)));
        when(grainRepository.findBySpaceIdAndStatus(spaceId, "active")).thenReturn(List.of(grain));
        when(knowledgeGapRepository.findOpenMatches(any(), any(), any(), any())).thenReturn(List.of());

        RagPipelineService.RagContext ctx = new RagPipelineService.RagContext(
                UUID.randomUUID(), UUID.randomUUID(), "客户说太贵了怎么办");
        RagPipelineService.GrainResult result = service.retrieveGrainsWithScores(
                "客户说太贵了怎么办", spaceId, 5, null, ctx, true);

        assertTrue(result.tiers().isEmpty());
        ArgumentCaptor<com.aiextract.model.KnowledgeGap> captor =
                ArgumentCaptor.forClass(com.aiextract.model.KnowledgeGap.class);
        verify(knowledgeGapRepository).save(captor.capture());
        assertTrue(captor.getValue().getNote().contains("all_low_similarity"));
        assertTrue(captor.getValue().getNote().contains("bestSim=0.100"));
    }

    @Test
    void enterpriseGapWithoutSkillIdIsPersisted() {
        UUID spaceId = UUID.randomUUID();
        when(grainRepository.findBySpaceIdAndStatus(spaceId, "active")).thenReturn(List.of());
        when(knowledgeGapRepository.findOpenMatches(any(), any(), any(), any())).thenReturn(List.of());

        RagPipelineService.RagContext ctx = new RagPipelineService.RagContext(
                null, UUID.randomUUID(), "企业级没有命中");
        service.writeKnowledgeGap("企业级没有命中", spaceId, ctx);

        ArgumentCaptor<com.aiextract.model.KnowledgeGap> captor =
                ArgumentCaptor.forClass(com.aiextract.model.KnowledgeGap.class);
        verify(knowledgeGapRepository).save(captor.capture());
        org.junit.jupiter.api.Assertions.assertNull(captor.getValue().getSkillId());
        org.junit.jupiter.api.Assertions.assertEquals(spaceId, captor.getValue().getSpaceId());
    }
}
