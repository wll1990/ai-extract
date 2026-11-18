package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.model.KnowledgeGap;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.KnowledgeGapRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.service.ContentSignalService;
import com.aiextract.service.TrainingAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 生成依据去重：同一句客户问题在多条缺口/多次评测里重复出现时，只能保留一条。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentSignalServiceTest {

    @Mock private KnowledgeGapRepository gapRepository;
    @Mock private TrainingTaskRecordRepository recordRepository;
    @Mock private EvaluationRunItemRepository runItemRepository;
    @Mock private KnowledgeEntryRepository knowledgeEntryRepository;
    @Mock private IndustryCaseRepository caseRepository;
    @Mock private TrainingAccessService accessService;
    @Mock private CompanyScopeService companyScopeService;

    private ContentSignalService service() {
        return new ContentSignalService(gapRepository, recordRepository, runItemRepository,
                knowledgeEntryRepository, caseRepository, accessService, companyScopeService);
    }

    private KnowledgeGap gap(String query, String scene) {
        return KnowledgeGap.builder()
                .id(UUID.randomUUID())
                .query(query)
                .sceneTag(scene)
                .attemptedQueryCount(1)
                .build();
    }

    @Test
    void recentSignals_dedupesNormalizedTextAndKeepsFirstSignal() {
        UUID companyId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        when(accessService.requireCompanyId()).thenReturn(companyId);
        when(companyScopeService.getSpaceIds(companyId)).thenReturn(Set.of(spaceId));
        when(gapRepository.findBySpaceIdInAndStatusOrderByAttemptedQueryCountDesc(
                any(), eq("open"), any(Pageable.class))).thenReturn(List.of(
                gap("客户说预算没批", "预算异议"),
                gap("  客户说预算没批 ", null),
                gap("客户骂我", null)));
        when(recordRepository.countFailedByScene(companyId)).thenReturn(List.<Object[]>of(
                new Object[]{"价格异议", 2}));
        when(runItemRepository.findRecentFailedItems(companyId, 10)).thenReturn(List.<Object[]>of(
                new Object[]{UUID.randomUUID(), "客户说预算没批", "预算异议"},
                new Object[]{UUID.randomUUID(), "客户骂我", null},
                new Object[]{UUID.randomUUID(), "你们团队在价格谈判上一般怎么做?", "价格异议"}));

        List<ContentSignalService.Signal> signals = service().recentSignals(10);

        // 两条重复预算问题合并成一条；客户骂我（gap 先出现）也保留一条；
        // 训练失败、谈判问题各一条 → 总共 4 条
        assertEquals(4, signals.size());
        assertEquals("客户说预算没批", signals.get(0).text());
        assertEquals("预算异议", signals.get(0).sceneTag());
        assertEquals("knowledge_gap", signals.get(0).type());
        assertEquals("客户骂我", signals.get(1).text());
        assertEquals("training_failure", signals.get(2).type());
        assertEquals("你们团队在价格谈判上一般怎么做?", signals.get(3).text());
        assertEquals("eval_issue", signals.get(3).type());
    }

    @Test
    void normalizeText_treatsWhitespaceAndCaseAsSame() {
        assertEquals(ContentSignalService.normalizeText("  ABC   def "),
                ContentSignalService.normalizeText("abc def"));
    }
}
