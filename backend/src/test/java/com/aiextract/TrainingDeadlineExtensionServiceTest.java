package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.service.TrainingAccessService;
import com.aiextract.service.TrainingAuditService;
import com.aiextract.service.TrainingDeadlineExtensionService;
import com.aiextract.service.TrainingNotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
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
 * 截止延期：只向后、只影响进行中考试、只延长不缩短。
 */
@ExtendWith(MockitoExtension.class)
class TrainingDeadlineExtensionServiceTest {

    @Mock private TrainingAssignmentRepository assignmentRepository;
    @Mock private TrainingAttemptRepository attemptRepository;
    @Mock private TrainingTaskRepository taskRepository;
    @Mock private TrainingAccessService accessService;
    @Mock private CompanyScopeService companyScopeService;
    @Mock private TrainingAuditService auditService;
    @Mock private TrainingNotificationService notificationService;
    @Mock private org.springframework.beans.factory.ObjectProvider<TrainingDeadlineExtensionService> selfProvider;

    private TrainingDeadlineExtensionService service;

    private final UUID reviewerId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID cohortId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingDeadlineExtensionService(assignmentRepository, attemptRepository,
                taskRepository, accessService, companyScopeService, auditService, notificationService,
                selfProvider);
        TokenContext.set(reviewerId, companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void extend_recalculatesInProgressExamDeadline() {
        LocalDateTime oldDeadline = LocalDateTime.now().plusMinutes(30);
        TrainingAssignment assignment = assignment(TrainingAssignment.STATUS_IN_PROGRESS, oldDeadline);
        stubScope(assignment);
        LocalDateTime startedAt = LocalDateTime.now().minusMinutes(1);
        TrainingAttempt attempt = TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskId(taskId)
                .type(TrainingAttempt.TYPE_EXAM).status(TrainingAttempt.STATUS_IN_PROGRESS)
                .startedAt(startedAt).deadline(oldDeadline).build();
        when(attemptRepository.findInProgressExamAttempts(assignmentId)).thenReturn(List.of(attempt));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(TrainingTask.builder()
                .id(taskId).planId(UUID.randomUUID()).version(1)
                .type(TrainingTask.TYPE_EXAM).title("考试").examDurationMinutes(60)
                .phaseNo(1).orderNo(1).build()));
        when(assignmentRepository.extendDeadline(eq(assignmentId), any(), eq(oldDeadline), any(), anyString()))
                .thenReturn(1);
        LocalDateTime newDeadline = LocalDateTime.now().plusDays(3);
        when(attemptRepository.extendAttemptDeadline(eq(attemptId), eq(oldDeadline), any())).thenReturn(1);

        TrainingDeadlineExtensionService.ExtensionResult result =
                service.extend(assignmentId, newDeadline, "学员休假");

        assertEquals(1, result.updatedAttempts());
        assertEquals(newDeadline, result.newDeadline());
        verify(attemptRepository).extendAttemptDeadline(eq(attemptId), eq(oldDeadline), any());
        verify(auditService).record(eq("training.deadline.extend"), anyString(), eq(assignmentId), any());
        verify(notificationService).notifyLearner(eq(assignment), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void extend_backwards_rejected() {
        stubScope(assignment(TrainingAssignment.STATUS_IN_PROGRESS, LocalDateTime.now().plusDays(2)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.extend(assignmentId, LocalDateTime.now().plusDays(1), "缩短"));

        assertEquals("DEADLINE_NOT_FORWARD", ex.getSubCode());
        verify(assignmentRepository, never()).extendDeadline(any(), any(), any(), any(), any());
    }

    @Test
    void extend_toPast_rejected() {
        stubScope(assignment(TrainingAssignment.STATUS_IN_PROGRESS, null));

        assertEquals("DEADLINE_NOT_FORWARD", assertThrows(BusinessException.class,
                () -> service.extend(assignmentId, LocalDateTime.now().minusHours(1), "过去")).getSubCode());
    }

    @Test
    void extend_certified_rejected() {
        stubScope(assignment(TrainingAssignment.STATUS_CERTIFIED, LocalDateTime.now().plusDays(1)));

        assertEquals("DEADLINE_NOT_EXTENDABLE", assertThrows(BusinessException.class,
                () -> service.extend(assignmentId, LocalDateTime.now().plusDays(3), "延期")).getSubCode());
    }

    @Test
    void extend_practiceOnly_movesAssignmentDeadline() {
        LocalDateTime oldDeadline = LocalDateTime.now().plusDays(1);
        TrainingAssignment assignment = assignment(TrainingAssignment.STATUS_IN_PROGRESS, oldDeadline);
        stubScope(assignment);
        when(attemptRepository.findInProgressExamAttempts(assignmentId)).thenReturn(List.of());
        when(assignmentRepository.extendDeadline(eq(assignmentId), any(), eq(oldDeadline), any(), anyString()))
                .thenReturn(1);

        TrainingDeadlineExtensionService.ExtensionResult result =
                service.extend(assignmentId, LocalDateTime.now().plusDays(5), "延期");

        assertEquals(0, result.updatedAttempts());
        verify(attemptRepository, never()).extendAttemptDeadline(any(), any(), any());
    }

    @Test
    void extend_self_rejected() {
        TrainingAssignment own = TrainingAssignment.builder()
                .id(assignmentId).companyId(companyId).cohortId(cohortId)
                .learnerId(reviewerId).status(TrainingAssignment.STATUS_IN_PROGRESS)
                .deadline(LocalDateTime.now().plusDays(1)).build();
        stubScope(own);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.extend(assignmentId, LocalDateTime.now().plusDays(3), "给自己延期"));

        assertEquals(403, ex.getErrorCode());
        verify(assignmentRepository, never()).extendDeadline(any(), any(), any(), any(), any());
    }

    @Test
    void extend_self_allowedForSuperAdmin() {
        TrainingAssignment own = TrainingAssignment.builder()
                .id(assignmentId).companyId(companyId).cohortId(cohortId)
                .learnerId(reviewerId).status(TrainingAssignment.STATUS_IN_PROGRESS)
                .deadline(LocalDateTime.now().plusDays(1)).build();
        stubScope(own);
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(true);
        when(attemptRepository.findInProgressExamAttempts(assignmentId)).thenReturn(List.of());
        when(assignmentRepository.extendDeadline(eq(assignmentId), any(), any(), any(), anyString()))
                .thenReturn(1);

        TrainingDeadlineExtensionService.ExtensionResult result =
                service.extend(assignmentId, LocalDateTime.now().plusDays(3), "超管运维");

        assertEquals(LocalDateTime.now().plusDays(3).getDayOfYear(), result.newDeadline().getDayOfYear());
    }

    @Test
    void applyForRemediation_allowsPendingCertification() {
        LocalDateTime oldDeadline = LocalDateTime.now().plusDays(1);
        TrainingAssignment assignment = assignment(
                TrainingAssignment.STATUS_PENDING_CERTIFICATION, oldDeadline);
        when(attemptRepository.findInProgressExamAttempts(assignmentId)).thenReturn(List.of());
        when(assignmentRepository.extendDeadline(eq(assignmentId), any(), eq(oldDeadline), any(), anyString()))
                .thenReturn(1);

        TrainingDeadlineExtensionService.ExtensionResult result =
                service.applyForRemediation(assignment, LocalDateTime.now().plusDays(5), "补训延期：选修");

        assertEquals(0, result.updatedAttempts());
    }

    @Test
    void applyLocked_pendingCertification_rejectedForManualExtension() {
        TrainingAssignment assignment = assignment(
                TrainingAssignment.STATUS_PENDING_CERTIFICATION, LocalDateTime.now().plusDays(1));

        // 手工延期仍只允许 not_started/in_progress（补训走 applyForRemediation）
        assertEquals("DEADLINE_NOT_EXTENDABLE", assertThrows(BusinessException.class,
                () -> service.applyLocked(assignment, LocalDateTime.now().plusDays(3), "延期"))
                .getSubCode());
    }

    @Test
    void extendCohort_perPersonReceipt_skipsClosedAssignments() {
        LocalDateTime oldDeadline = LocalDateTime.now().plusDays(1);
        TrainingAssignment open = assignment(TrainingAssignment.STATUS_IN_PROGRESS, oldDeadline);
        TrainingAssignment certified = TrainingAssignment.builder()
                .id(UUID.randomUUID()).companyId(companyId).cohortId(cohortId)
                .learnerId(UUID.randomUUID()).status(TrainingAssignment.STATUS_CERTIFIED)
                .deadline(oldDeadline).build();
        when(assignmentRepository.findByCohortIdOrderByCreatedAtAsc(cohortId))
                .thenReturn(List.of(open, certified));
        when(accessService.assertCanReview(cohortId)).thenReturn(
                TrainingCohort.builder().id(cohortId).companyId(companyId).build());
        when(selfProvider.getObject()).thenReturn(service);
        stubScope(open);
        when(attemptRepository.findInProgressExamAttempts(assignmentId)).thenReturn(List.of());
        when(assignmentRepository.extendDeadline(eq(assignmentId), any(), eq(oldDeadline), any(), anyString()))
                .thenReturn(1);

        TrainingDeadlineExtensionService.CohortExtensionResult result =
                service.extendCohort(cohortId, LocalDateTime.now().plusDays(5), "批次统一延期");

        assertEquals(1, result.extended());
        assertEquals(1, result.skipped(), "已认证的学员不应被延期");
        assertEquals(2, result.items().size());
        verify(auditService).record(eq("training.deadline.extend_cohort"), eq("training_cohort"),
                eq(cohortId), any());
    }

    private void stubScope(TrainingAssignment assignment) {
        lenient().when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        lenient().when(assignmentRepository.findByIdForUpdate(assignmentId)).thenReturn(Optional.of(assignment));
        lenient().when(accessService.assertCanReview(cohortId)).thenReturn(
                TrainingCohort.builder().id(cohortId).companyId(companyId).build());
    }

    private TrainingAssignment assignment(String status, LocalDateTime deadline) {
        return TrainingAssignment.builder()
                .id(assignmentId).companyId(companyId).cohortId(cohortId)
                .learnerId(UUID.randomUUID()).status(status).deadline(deadline).build();
    }
}
