package com.aiextract;

import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.service.TrainingDeadlineService;
import com.aiextract.service.TrainingProgressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TrainingDeadlineService 单元测试 —— 两种逾期策略、分配状态守卫、脏行旁路。
 */
@ExtendWith(MockitoExtension.class)
class TrainingDeadlineServiceTest {

    @Mock private TrainingAttemptRepository attemptRepository;
    @Mock private TrainingAssignmentRepository assignmentRepository;
    @Mock private TrainingTaskRepository taskRepository;
    @Mock private TrainingTaskRecordRepository recordRepository;
    @Mock private TrainingProgressService progressService;
    @Mock private com.aiextract.service.TrainingNotificationService notificationService;

    private TrainingDeadlineService service;

    private final UUID attemptId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingDeadlineService(attemptRepository, assignmentRepository,
                taskRepository, recordRepository, progressService, notificationService);
    }

    @Test
    void autoSubmit_submitsWithoutProgressWrite() {
        stubOpenAttempt();
        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task(TrainingTask.DEADLINE_AUTO_SUBMIT)));
        when(recordRepository.existsById(recordId)).thenReturn(true);
        when(attemptRepository.submitOverdue(attemptId)).thenReturn(1);

        service.handleOverdue(attemptId);

        verify(attemptRepository).submitOverdue(attemptId);
        verify(attemptRepository, never()).markOverdueReview(any());
        verifyNoInteractions(progressService);
    }

    @Test
    void timeoutFail_goesPendingReviewInsteadOfFinalFail() {
        stubOpenAttempt();
        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task(TrainingTask.DEADLINE_TIMEOUT_FAIL)));
        when(recordRepository.existsById(recordId)).thenReturn(true);
        when(attemptRepository.markOverdueReview(attemptId)).thenReturn(1);

        service.handleOverdue(attemptId);

        verify(attemptRepository).markOverdueReview(attemptId);
        // 超时结论必须给主管一次裁决机会
        verify(progressService).markPendingReview(recordId);
        verify(attemptRepository, never()).submitOverdue(any());
    }

    @Test
    void timeoutFail_conditionMissed_doesNotTouchProgress() {
        stubOpenAttempt();
        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task(TrainingTask.DEADLINE_TIMEOUT_FAIL)));
        when(recordRepository.existsById(recordId)).thenReturn(true);
        when(attemptRepository.markOverdueReview(attemptId)).thenReturn(0);

        service.handleOverdue(attemptId);

        verify(progressService, never()).markPendingReview(any());
    }

    @Test
    void cancelledAssignment_isBypassedAndRemovedFromQueue() {
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_IN_PROGRESS)));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId)
                        .status(TrainingAssignment.STATUS_CANCELLED).build()));

        service.handleOverdue(attemptId);

        verify(attemptRepository).markSweepError(eq(attemptId), anyString());
        verifyNoInteractions(taskRepository, progressService);
    }

    @Test
    void notInProgress_isNoop() {
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_SUBMITTED)));

        service.handleOverdue(attemptId);

        verifyNoInteractions(assignmentRepository, taskRepository, progressService);
    }

    @Test
    void missingTask_marksSweepErrorSoQueueDoesNotStarve() {
        stubOpenAttempt();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        service.handleOverdue(attemptId);

        verify(attemptRepository).markSweepError(eq(attemptId), anyString());
        verify(progressService).markPendingReview(recordId);
        verify(attemptRepository, never()).submitOverdue(any());
        verify(attemptRepository, never()).markOverdueReview(any());
    }

    @Test
    void missingRecord_marksSweepErrorWithoutProgressWrite() {
        stubOpenAttempt();
        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task(TrainingTask.DEADLINE_TIMEOUT_FAIL)));
        when(recordRepository.existsById(recordId)).thenReturn(false);

        service.handleOverdue(attemptId);

        verify(attemptRepository).markSweepError(eq(attemptId), anyString());
        verifyNoInteractions(progressService);
    }

    // ==================== 辅助 ====================

    private void stubOpenAttempt() {
        LocalDateTime past = LocalDateTime.now().minusMinutes(1);
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(TrainingAttempt.builder()
                        .id(attemptId).assignmentId(assignmentId)
                        .taskId(taskId).taskRecordId(recordId)
                        .status(TrainingAttempt.STATUS_IN_PROGRESS)
                        .deadline(past).build()));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId)
                        .status(TrainingAssignment.STATUS_IN_PROGRESS)
                        .deadline(past).build()));
    }

    @Test
    void extendedDeadline_isSkippedBySecondCheck() {
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(
                TrainingAttempt.builder().id(attemptId).assignmentId(assignmentId)
                        .taskId(taskId).taskRecordId(recordId)
                        .status(TrainingAttempt.STATUS_IN_PROGRESS)
                        .deadline(LocalDateTime.now().plusHours(2)).build()));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId)
                        .status(TrainingAssignment.STATUS_IN_PROGRESS)
                        .deadline(LocalDateTime.now().plusHours(2)).build()));

        service.handleOverdue(attemptId);

        verifyNoInteractions(taskRepository, progressService);
        verify(attemptRepository, never()).submitOverdue(any());
        verify(attemptRepository, never()).markOverdueReview(any());
    }

    private TrainingAttempt attempt(String status) {
        return TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId)
                .taskId(taskId).taskRecordId(recordId).status(status).build();
    }

    private TrainingTask task(String policy) {
        return TrainingTask.builder()
                .id(taskId).planId(UUID.randomUUID()).version(1)
                .type(TrainingTask.TYPE_EXAM).title("考试").phaseNo(1).orderNo(1)
                .deadlinePolicy(policy).build();
    }
}
