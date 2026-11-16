package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.model.KnowledgeGap;
import com.aiextract.repository.KnowledgeGapRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.service.TrainingAccessService;
import com.aiextract.service.TrainingNeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * AI 培训需求聚合：知识缺口与实战失败按场景合并，失败权重更高。
 */
@ExtendWith(MockitoExtension.class)
class TrainingNeedServiceTest {

    @Mock private KnowledgeGapRepository gapRepository;
    @Mock private TrainingTaskRecordRepository recordRepository;
    @Mock private TrainingAccessService accessService;
    @Mock private CompanyScopeService companyScopeService;

    private TrainingNeedService service;
    private final UUID companyId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingNeedService(gapRepository, recordRepository, accessService, companyScopeService);
        when(accessService.requireCompanyId()).thenReturn(companyId);
        when(companyScopeService.getSpaceIds(companyId)).thenReturn(Set.of(spaceId));
        when(gapRepository.countBySpaceIdInAndStatus(any(), anyString())).thenReturn(2L);
    }

    @Test
    void needs_mergesGapAndFailedByScene() {
        when(gapRepository.findBySpaceIdInAndStatusOrderByAttemptedQueryCountDesc(any(), eq("open"), any()))
                .thenReturn(List.of(
                        gap("价格异议", "客户说太贵怎么办", 3),
                        gap("竞品对比", "和友商比优势是什么", 1)));
        when(recordRepository.countFailedByScene(companyId)).thenReturn(
                List.<Object[]>of(new Object[]{"价格异议", 2L}));

        TrainingNeedService.NeedsView view = service.needs(10);

        assertEquals(2, view.openGaps());
        assertEquals(2, view.failedTasks());
        assertEquals(2, view.dimensions().size());
        var price = view.dimensions().stream()
                .filter(d -> "价格异议".equals(d.sceneTag())).findFirst().orElseThrow();
        assertEquals(3, price.gapCount());
        assertEquals(2, price.failedCount());
        assertEquals(3 * 2 + 2 * 3, price.weight(), "失败比提问更重");
        assertTrue(price.sampleQueries().contains("客户说太贵怎么办"));
        assertTrue(price.recommendation().contains("场景微练"));
    }

    @Test
    void needs_withoutSignals_returnsEmpty() {
        when(gapRepository.findBySpaceIdInAndStatusOrderByAttemptedQueryCountDesc(any(), eq("open"), any()))
                .thenReturn(List.of());
        when(recordRepository.countFailedByScene(companyId)).thenReturn(List.of());

        TrainingNeedService.NeedsView view = service.needs(10);

        assertTrue(view.dimensions().isEmpty());
    }

    private KnowledgeGap gap(String scene, String query, int count) {
        return KnowledgeGap.builder()
                .id(UUID.randomUUID()).spaceId(spaceId).sceneTag(scene)
                .query(query).attemptedQueryCount(count).status("open").build();
    }
}
