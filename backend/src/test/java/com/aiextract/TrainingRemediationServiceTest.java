package com.aiextract;

import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.service.TrainingAccessService;
import com.aiextract.service.TrainingAuditService;
import com.aiextract.service.TrainingDeadlineExtensionService;
import com.aiextract.service.TrainingNotificationService;
import com.aiextract.service.TrainingProgressService;
import com.aiextract.service.TrainingRemediationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补训核心规则：只有最新一轮失败可补；计数由进度服务重算；过期必须延期。
 */
@ExtendWith(MockitoExtension.class)
class TrainingRemediationServiceTest {

    @Mock private TrainingAssignmentRepository assignmentRepository;
    @Mock private TrainingTaskRecordRepository recordRepository;
    @Mock private TrainingTaskRepository taskRepository;
    @Mock private com.aiextract.repository.TrainingAttemptRepository attemptRepository;
    @Mock private TrainingAccessService accessService;
    @Mock private TrainingDeadlineExtensionService deadlineExtensionService;
    @Mock private TrainingProgressService progressService;
    @Mock private TrainingAuditService auditService;
    @Mock private TrainingNotificationService notificationService;

    private TrainingRemediationService service;

    private final UUID reviewerId = UUID.randomUUID();
    private final UUID learnerId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID cohortId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingRemediationService(assignmentRepository, recordRepository,
                taskRepository, attemptRepository, accessService, deadlineExtensionService, progressService,
                auditService, notificationService);
        ReflectionTestUtils.setField(service, "maxRounds", 10);
        TokenContext.set(reviewerId, companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void create_failedLatest_appendsNextRound() {
        stubAssignment(assignment(LocalDateTime.now().plusDays(3)));
        stubTarget(TrainingTaskRecord.STATUS_FAILED, 1);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task(TrainingTask.TYPE_EXAM)));

        TrainingRemediationService.RemediationResult result =
                service.create(assignmentId, recordId, "异议处理不达标", null);

        assertEquals(2, result.roundNo());
        assertEquals(TrainingTaskRecord.STATUS_AVAILABLE, result.record().getStatus());
        assertEquals(recordId, result.record().getRemediationOfRecordId());
        assertEquals("异议处理不达标", result.record().getRemediationReason());
        verify(recordRepository).saveAndFlush(any(TrainingTaskRecord.class));
        verify(progressService).reevaluateCertificationState(assignmentId);
        verify(auditService).record(eq("training.remediation.create"), anyString(), eq(assignmentId), any());
    }

    @Test
    void create_passedTarget_rejected() {
        stubAssignment(assignment(LocalDateTime.now().plusDays(3)));
        stubTarget(TrainingTaskRecord.STATUS_PASSED, 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(assignmentId, recordId, "补训", null));

        assertEquals(409, ex.getErrorCode());
        assertEquals("REMEDIATION_TARGET_INVALID", ex.getSubCode());
        verify(recordRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_notLatestRound_rejected() {
        stubAssignment(assignment(LocalDateTime.now().plusDays(3)));
        stubTarget(TrainingTaskRecord.STATUS_FAILED, 1);
        when(recordRepository.findMaxRoundNo(assignmentId, taskId)).thenReturn(2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(assignmentId, recordId, "补训", null));

        assertEquals("REMEDIATION_NOT_LATEST", ex.getSubCode());
    }

    @Test
    void create_readingTask_rejected() {
        stubAssignment(assignment(LocalDateTime.now().plusDays(3)));
        stubTarget(TrainingTaskRecord.STATUS_FAILED, 1);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task(TrainingTask.TYPE_READING)));

        assertEquals("REMEDIATION_TARGET_INVALID", assertThrows(BusinessException.class,
                () -> service.create(assignmentId, recordId, "补训", null)).getSubCode());
    }

    @Test
    void create_certifiedAssignment_rejected() {
        TrainingAssignment certified = assignment(LocalDateTime.now().plusDays(3));
        certified.setStatus(TrainingAssignment.STATUS_CERTIFIED);
        stubAssignment(certified);

        assertEquals("REMEDIATION_ASSIGNMENT_CLOSED", assertThrows(BusinessException.class,
                () -> service.create(assignmentId, recordId, "补训", null)).getSubCode());
    }

    @Test
    void create_overdueWithoutDeadline_rejected() {
        stubAssignment(assignment(LocalDateTime.now().minusDays(1)));
        stubTarget(TrainingTaskRecord.STATUS_FAILED, 1);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task(TrainingTask.TYPE_EXAM)));

        assertEquals("REMEDIATION_DEADLINE_REQUIRED", assertThrows(BusinessException.class,
                () -> service.create(assignmentId, recordId, "补训", null)).getSubCode());
    }

    @Test
    void create_overdueWithDeadline_extendsAssignment() {
        TrainingAssignment overdue = assignment(LocalDateTime.now().minusDays(1));
        stubAssignment(overdue);
        stubTarget(TrainingTaskRecord.STATUS_FAILED, 1);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task(TrainingTask.TYPE_EXAM)));
        LocalDateTime newDeadline = LocalDateTime.now().plusDays(7);
        when(deadlineExtensionService.applyForRemediation(overdue, newDeadline, "补训延期：补训"))
                .thenReturn(new TrainingDeadlineExtensionService.ExtensionResult(
                        overdue, overdue.getDeadline(), newDeadline, 0));

        service.create(assignmentId, recordId, "补训", newDeadline);

        // 补训可能落在 pending_certification 上，必须走允许该状态的延期入口
        verify(deadlineExtensionService).applyForRemediation(overdue, newDeadline, "补训延期：补训");
    }

    @Test
    void create_limitReached_conflicts() {
        stubAssignment(assignment(LocalDateTime.now().plusDays(3)));
        stubTarget(TrainingTaskRecord.STATUS_FAILED, 10);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task(TrainingTask.TYPE_EXAM)));

        assertEquals("REMEDIATION_LIMIT", assertThrows(BusinessException.class,
                () -> service.create(assignmentId, recordId, "补训", null)).getSubCode());
    }

    @Test
    void create_onPendingCertification_reevaluatesStatus() {
        TrainingAssignment pending = assignment(LocalDateTime.now().plusDays(3));
        pending.setStatus(TrainingAssignment.STATUS_PENDING_CERTIFICATION);
        stubAssignment(pending);
        stubTarget(TrainingTaskRecord.STATUS_FAILED, 1);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task(TrainingTask.TYPE_EXAM)));

        service.create(assignmentId, recordId, "选修任务补训", null);

        // 状态由进度服务统一判定（含未完成补训 -> in_progress），不再硬编码降级
        verify(progressService).reevaluateCertificationState(assignmentId);
        verify(assignmentRepository, never()).updateStatus(assignmentId,
                TrainingAssignment.STATUS_PENDING_CERTIFICATION,
                TrainingAssignment.STATUS_IN_PROGRESS);
    }

    @Test
    void create_self_rejected() {
        TokenContext.set(learnerId, companyId);
        stubAssignment(assignment(LocalDateTime.now().plusDays(3)));

        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.create(assignmentId, recordId, "补训", null)).getErrorCode());
    }

    @Test
    void reopen_passedLatest_appendsReopenRound() {
        stubAssignment(assignment(LocalDateTime.now().plusDays(3)));
        stubTarget(TrainingTaskRecord.STATUS_PASSED, 1);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task(TrainingTask.TYPE_EXAM)));

        TrainingRemediationService.RemediationResult result =
                service.reopen(assignmentId, recordId, "AI 误判，实际未处理异议", null);

        assertEquals(2, result.roundNo());
        assertEquals("reopen", result.record().getRoundType());
        assertEquals(TrainingTaskRecord.STATUS_AVAILABLE, result.record().getStatus());
        assertEquals(recordId, result.record().getRemediationOfRecordId());
        verify(progressService).recomputeCounters(assignmentId);
        verify(progressService).reevaluateCertificationState(assignmentId);
        verify(auditService).record(eq("training.reopen"), eq("training_assignment"),
                eq(assignmentId), any());
    }

    @Test
    void reopen_failedTarget_rejected() {
        stubAssignment(assignment(LocalDateTime.now().plusDays(3)));
        stubTarget(TrainingTaskRecord.STATUS_FAILED, 1);

        assertEquals("REOPEN_TARGET_INVALID", assertThrows(BusinessException.class,
                () -> service.reopen(assignmentId, recordId, "误判要求重做", null)).getSubCode());
    }

    @Test
    void reopen_readingTask_rejected() {
        stubAssignment(assignment(LocalDateTime.now().plusDays(3)));
        stubTarget(TrainingTaskRecord.STATUS_PASSED, 1);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task(TrainingTask.TYPE_READING)));

        assertEquals("REOPEN_TARGET_INVALID", assertThrows(BusinessException.class,
                () -> service.reopen(assignmentId, recordId, "误判要求重做", null)).getSubCode());
    }

    @Test
    void reopen_certifiedAssignment_rejected() {
        TrainingAssignment certified = assignment(LocalDateTime.now().plusDays(3));
        certified.setStatus(TrainingAssignment.STATUS_CERTIFIED);
        stubAssignment(certified);

        assertEquals("REOPEN_ASSIGNMENT_CLOSED", assertThrows(BusinessException.class,
                () -> service.reopen(assignmentId, recordId, "误判要求重做", null)).getSubCode());
    }

    // ==================== 辅助 ====================

    private void stubAssignment(TrainingAssignment assignment) {
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.findByIdForUpdate(assignmentId)).thenReturn(Optional.of(assignment));
        when(accessService.assertCanReview(cohortId)).thenReturn(
                TrainingCohort.builder().id(cohortId).companyId(companyId).build());
        lenient().when(assignmentRepository.updateStatus(any(), anyString(), anyString())).thenReturn(1);
    }

    private void stubTarget(String status, int round) {
        when(recordRepository.findByIdAndAssignmentId(recordId, assignmentId)).thenReturn(Optional.of(
                TrainingTaskRecord.builder().id(recordId).assignmentId(assignmentId)
                        .taskId(taskId).roundNo(round).status(status).build()));
        when(recordRepository.findMaxRoundNo(assignmentId, taskId)).thenReturn(round);
    }

    private TrainingAssignment assignment(LocalDateTime deadline) {
        return TrainingAssignment.builder()
                .id(assignmentId).companyId(companyId).cohortId(cohortId)
                .learnerId(learnerId).status(TrainingAssignment.STATUS_IN_PROGRESS)
                .totalTasks(1).requiredTotal(1).completedTasks(1).passedTasks(0).requiredPassed(0)
                .deadline(deadline).build();
    }

    private TrainingTask task(String type) {
        return TrainingTask.builder()
                .id(taskId).planId(UUID.randomUUID()).version(1).phaseNo(1).orderNo(1)
                .type(type).title("任务").isRequired(true).build();
    }
}
