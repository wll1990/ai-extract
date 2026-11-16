package com.aiextract;

import com.aiextract.model.EvaluationRun;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.EvaluationRunRepository;
import com.aiextract.service.EvaluationRunAggregator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EvaluationRunAggregator 单元测试 —— 计数口径与收尾判定（Worker 与人工复核共用）。
 */
@ExtendWith(MockitoExtension.class)
class EvaluationRunAggregatorTest {

    @Mock
    private EvaluationRunRepository runRepository;
    @Mock
    private EvaluationRunItemRepository runItemRepository;

    private EvaluationRunAggregator aggregator;
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        aggregator = new EvaluationRunAggregator(runRepository, runItemRepository);
        // 不是每个用例都会走到收尾，用 lenient 避免严格打桩报错
        lenient().when(runRepository.finish(eq(runId), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void recompute_countsLatestStatusesAndDoesNotFinishWhileExecuting() {
        stubStatuses(new Object[]{"completed", 2L}, new Object[]{"pending", 1L});
        stubVerdicts(new Object[]{"pass", 2L});
        when(runItemRepository.avgTotalScoreByRun(runId)).thenReturn(82.456);

        boolean finished = aggregator.recomputeAndMaybeFinish(runId);

        assertFalse(finished);
        // 平均分四舍五入到两位
        verify(runRepository).updateCounters(eq(runId), eq(3), eq(2), eq(0), eq(2), eq(0), eq(0),
                eq(new BigDecimal("82.46")), any());
        verify(runRepository, never()).finish(any(), any(), any(), any(), any());
    }

    @Test
    void recompute_allCompleted_finishesAsCompletedPendingReview() {
        stubStatuses(new Object[]{"completed", 3L});
        stubVerdicts(new Object[]{"pass", 2L}, new Object[]{"fail", 1L});
        when(runItemRepository.avgTotalScoreByRun(runId)).thenReturn(70.0);

        boolean finished = aggregator.recomputeAndMaybeFinish(runId);

        assertTrue(finished);
        verify(runRepository).finish(eq(runId), eq(EvaluationRun.STATUS_COMPLETED),
                eq(EvaluationRun.REVIEW_PENDING), any(), any());
    }

    @Test
    void recompute_partialError_finishesAsPartialFailed() {
        stubStatuses(new Object[]{"completed", 2L}, new Object[]{"error", 1L});
        stubVerdicts(new Object[]{"pass", 2L});
        when(runItemRepository.avgTotalScoreByRun(runId)).thenReturn(66.0);

        aggregator.recomputeAndMaybeFinish(runId);

        verify(runRepository).finish(eq(runId), eq(EvaluationRun.STATUS_PARTIAL_FAILED),
                eq(EvaluationRun.REVIEW_PENDING), any(), any());
    }

    @Test
    void recompute_allError_finishesAsFailedWithoutReview() {
        stubStatuses(new Object[]{"error", 2L});
        stubVerdicts();
        when(runItemRepository.avgTotalScoreByRun(runId)).thenReturn(null);

        aggregator.recomputeAndMaybeFinish(runId);

        verify(runRepository).finish(eq(runId), eq(EvaluationRun.STATUS_FAILED),
                eq(EvaluationRun.REVIEW_NOT_READY), any(), any());
    }

    @Test
    void recompute_zeroItems_finishesAsFailed() {
        stubStatuses();
        stubVerdicts();
        when(runItemRepository.avgTotalScoreByRun(runId)).thenReturn(null);

        aggregator.recomputeAndMaybeFinish(runId);

        verify(runRepository).finish(eq(runId), eq(EvaluationRun.STATUS_FAILED),
                eq(EvaluationRun.REVIEW_NOT_READY), any(), any());
    }

    @Test
    void recompute_concurrentCancel_doesNotReportFinished() {
        stubStatuses(new Object[]{"completed", 1L});
        stubVerdicts(new Object[]{"pass", 1L});
        when(runItemRepository.avgTotalScoreByRun(runId)).thenReturn(90.0);
        // 收尾时批次已被取消（finish 带状态守卫，影响 0 行）
        when(runRepository.finish(eq(runId), any(), any(), any(), any())).thenReturn(0);

        boolean finished = aggregator.recomputeAndMaybeFinish(runId);

        assertFalse(finished);
    }

    private void stubStatuses(Object[]... rows) {
        when(runItemRepository.countLatestStatusByRun(runId)).thenReturn(List.of(rows));
    }

    private void stubVerdicts(Object[]... rows) {
        when(runItemRepository.countLatestVerdictByRun(runId)).thenReturn(List.of(rows));
    }
}
