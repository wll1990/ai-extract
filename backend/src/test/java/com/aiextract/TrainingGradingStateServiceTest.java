package com.aiextract;

import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.service.TrainingGradingStateService;
import com.aiextract.service.TrainingProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TrainingGradingStateService 单元测试 —— 租约归属守卫、定稿回写、重试上限。
 */
@ExtendWith(MockitoExtension.class)
class TrainingGradingStateServiceTest {

    @Mock private TrainingAttemptRepository attemptRepository;
    @Mock private com.aiextract.repository.TrainingAssignmentRepository assignmentRepository;
    @Mock private com.aiextract.service.TrainingNotificationService notificationService;
    @Mock private TrainingProgressService progressService;

    private TrainingGradingStateService service;

    private final UUID attemptId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();
    private static final String OWNER = "worker-1";

    @BeforeEach
    void setUp() {
        service = new TrainingGradingStateService(attemptRepository, assignmentRepository, progressService, notificationService);
        lenient().when(assignmentRepository.findById(assignmentId))
                .thenReturn(Optional.of(TrainingAssignment.builder()
                        .id(assignmentId).status("in_progress").build()));
        lenient().when(attemptRepository.findById(any()))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_GRADING, 1)));
    }

    @Test
    void complete_finalizedPass_appliesRecordResult() {
        when(attemptRepository.completeGrading(eq(attemptId), eq(TrainingAttempt.STATUS_GRADING),
                eq(OWNER), eq(TrainingAttempt.STATUS_FINALIZED), eq(TrainingAttempt.RESULT_PASS),
                anyString(), any())).thenReturn(1);

        boolean done = service.complete(attemptId, recordId, OWNER,
                TrainingAttempt.STATUS_FINALIZED, TrainingAttempt.RESULT_PASS, "{}");

        assertTrue(done);
        verify(progressService).applyRecordResult(recordId, true);
    }

    @Test
    void complete_pendingReview_marksRecordWithoutCounters() {
        when(attemptRepository.completeGrading(eq(attemptId), eq(TrainingAttempt.STATUS_GRADING),
                eq(OWNER), eq(TrainingAttempt.STATUS_PENDING_REVIEW), eq(TrainingAttempt.RESULT_PASS),
                anyString(), any())).thenReturn(1);

        service.complete(attemptId, recordId, OWNER,
                TrainingAttempt.STATUS_PENDING_REVIEW, TrainingAttempt.RESULT_PASS, "{}");

        verify(progressService).markPendingReview(recordId);
        verify(progressService, never()).applyRecordResult(any(), eq(true));
    }

    @Test
    void complete_staleLease_doesNotTouchProgress() {
        // 租约已被其他 worker 接管：条件 UPDATE 命中 0 行
        when(attemptRepository.completeGrading(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any())).thenReturn(0);

        boolean done = service.complete(attemptId, recordId, OWNER,
                TrainingAttempt.STATUS_FINALIZED, TrainingAttempt.RESULT_PASS, "{}");

        assertFalse(done);
        verify(progressService, never()).applyRecordResult(any(), any(Boolean.class));
        verify(progressService, never()).markPendingReview(any());
    }

    @Test
    void failOrRequeue_underLimit_requeuesWithOwner() {
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_GRADING, 1)));

        service.failOrRequeue(attemptId, OWNER, 3, "模型超时");

        verify(attemptRepository).requeue(attemptId, TrainingAttempt.STATUS_GRADING, OWNER,
                TrainingAttempt.STATUS_SUBMITTED, "模型超时");
        verify(attemptRepository, never()).markError(any(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    @Test
    void failOrRequeue_atLimit_marksErrorAndOpensHumanReview() {
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_GRADING, 3)));
        when(attemptRepository.markError(eq(attemptId), eq(TrainingAttempt.STATUS_GRADING),
                eq(OWNER), eq(TrainingAttempt.STATUS_ERROR), eq("模型超时"), any())).thenReturn(1);

        service.failOrRequeue(attemptId, OWNER, 3, "模型超时");

        verify(attemptRepository).markError(eq(attemptId), eq(TrainingAttempt.STATUS_GRADING),
                eq(OWNER), eq(TrainingAttempt.STATUS_ERROR), eq("模型超时"), any());
        // AI 彻底失败不能让进度行永久卡在 in_progress
        verify(progressService).markPendingReview(recordId);
        verify(attemptRepository, never()).requeue(any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void failOrRequeue_staleLease_noop() {
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_GRADING, 1, "worker-2")));

        service.failOrRequeue(attemptId, OWNER, 3, "模型超时");

        verify(attemptRepository, never()).requeue(any(), anyString(), anyString(), anyString(), anyString());
        verify(attemptRepository, never()).markError(any(), anyString(), anyString(),
                anyString(), anyString(), any());
    }

    private TrainingAttempt attempt(String status, int gradingAttempts) {
        return attempt(status, gradingAttempts, OWNER);
    }

    private TrainingAttempt attempt(String status, int gradingAttempts, String leaseOwner) {
        return TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskRecordId(recordId).status(status)
                .gradingAttempts(gradingAttempts).leaseOwner(leaseOwner).build();
    }
}
