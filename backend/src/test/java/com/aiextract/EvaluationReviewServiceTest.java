package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.EvaluationReviewRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationReview;
import com.aiextract.model.EvaluationRun;
import com.aiextract.model.EvaluationRunItem;
import com.aiextract.repository.EvaluationReviewRepository;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.EvaluationRunRepository;
import com.aiextract.service.EvaluationReviewService;
import com.aiextract.service.EvaluationRunAggregator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EvaluationReviewService 单元测试 —— 复核动作语义、原分保留、结论重算与审核态回退。
 */
@ExtendWith(MockitoExtension.class)
class EvaluationReviewServiceTest {

    @Mock
    private EvaluationRunItemRepository runItemRepository;
    @Mock
    private EvaluationRunRepository runRepository;
    @Mock
    private EvaluationReviewRepository reviewRepository;
    @Mock
    private CompanyScopeService companyScopeService;
    @Mock
    private EvaluationRunAggregator aggregator;

    private EvaluationReviewService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID runItemId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new EvaluationReviewService(runItemRepository, runRepository, reviewRepository,
                companyScopeService, aggregator, objectMapper);
        ReflectionTestUtils.setField(service, "passThreshold", 60);
        TokenContext.set(userId, companyId);
        lenient().when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void adjust_aboveThreshold_setsPassAndKeepsOriginalScore() {
        stubCompletedItem("{\"total_score\":55,\"need_review\":true}");

        EvaluationReview review = service.review(runItemId, adjust("{\"total_score\":85}"));

        assertEquals(EvaluationReview.ACTION_ADJUST, review.getAction());
        assertEquals("{\"total_score\":55,\"need_review\":true}", review.getOriginalScore());
        assertEquals("{\"total_score\":85}", review.getAdjustedScore());
        verify(runItemRepository).updateVerdict(eq(runItemId), eq(EvaluationRunItem.VERDICT_PASS), any());
    }

    @Test
    void adjust_belowThreshold_setsFail() {
        stubCompletedItem("{\"total_score\":85}");

        service.review(runItemId, adjust("{\"total_score\":40}"));

        verify(runItemRepository).updateVerdict(eq(runItemId), eq(EvaluationRunItem.VERDICT_FAIL), any());
    }

    @Test
    void adjust_missingScore_rejected() {
        stubCompletedItem("{\"total_score\":85}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.review(runItemId, adjust(null)));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("adjustedScore"));
    }

    @Test
    void adjust_outOfRange_rejected() {
        stubCompletedItem("{\"total_score\":85}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.review(runItemId, adjust("{\"total_score\":150}")));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("0-100"));
    }

    @Test
    void undetermined_setsUndeterminedVerdict() {
        stubCompletedItem("{\"total_score\":85}");

        service.review(runItemId, request(EvaluationReview.ACTION_UNDETERMINED, null));

        verify(runItemRepository).updateVerdict(eq(runItemId),
                eq(EvaluationRunItem.VERDICT_UNDETERMINED), any());
    }

    @Test
    void confirm_keepsAiVerdict() {
        EvaluationRunItem item = stubCompletedItem("{\"total_score\":85}");
        item.setVerdict(EvaluationRunItem.VERDICT_PASS);

        service.review(runItemId, request(EvaluationReview.ACTION_CONFIRM, null));

        verify(runItemRepository).updateVerdict(eq(runItemId), eq(EvaluationRunItem.VERDICT_PASS), any());
    }

    @Test
    void review_executionNotCompleted_rejected() {
        EvaluationRunItem item = EvaluationRunItem.builder()
                .id(runItemId).runId(runId).status(EvaluationRunItem.STATUS_ERROR).build();
        when(runItemRepository.findById(runItemId)).thenReturn(Optional.of(item));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.review(runItemId, request(EvaluationReview.ACTION_CONFIRM, null)));

        assertEquals(409, ex.getErrorCode());
    }

    @Test
    void review_reopensReviewStatusAndReaggregates() {
        stubCompletedItem("{\"total_score\":85}");

        service.review(runItemId, request(EvaluationReview.ACTION_CONFIRM, null));

        verify(aggregator).recomputeAndMaybeFinish(runId);
        verify(runRepository).updateReviewStatus(eq(runId), eq(EvaluationRun.REVIEW_PENDING), any());
    }

    private EvaluationRunItem stubCompletedItem(String aiScore) {
        EvaluationRunItem item = EvaluationRunItem.builder()
                .id(runItemId)
                .runId(runId)
                .status(EvaluationRunItem.STATUS_COMPLETED)
                .verdict(EvaluationRunItem.VERDICT_FAIL)
                .aiScore(aiScore)
                .build();
        when(runItemRepository.findById(runItemId)).thenReturn(Optional.of(item));
        return item;
    }

    private EvaluationReviewRequest adjust(String adjustedScore) {
        return request(EvaluationReview.ACTION_ADJUST, adjustedScore);
    }

    private EvaluationReviewRequest request(String action, String adjustedScore) {
        return EvaluationReviewRequest.builder()
                .action(action)
                .reason("复核理由")
                .adjustedScore(adjustedScore)
                .build();
    }
}
