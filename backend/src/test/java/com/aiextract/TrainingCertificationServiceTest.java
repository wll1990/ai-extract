package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingCertification;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingCertificationRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.service.TrainingAccessService;
import com.aiextract.service.TrainingAuditService;
import com.aiextract.service.TrainingCertificationService;
import com.aiextract.service.TrainingNotificationService;
import com.aiextract.service.TrainingRecordAggregator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * TrainingCertificationService 单元测试 —— 必修门槛、自我认证、并发、幂等。
 */
@ExtendWith(MockitoExtension.class)
class TrainingCertificationServiceTest {

    @Mock private TrainingAssignmentRepository assignmentRepository;
    @Mock private TrainingCertificationRepository certificationRepository;
    @Mock private TrainingTaskRecordRepository recordRepository;
    @Mock private TrainingTaskRepository taskRepository;
    @Mock private com.aiextract.repository.TrainingAttemptRepository attemptRepository;
    @Mock private com.aiextract.repository.UserRepository userRepository;
    @Mock private TrainingAccessService accessService;
    @Mock private CompanyScopeService companyScopeService;
    @Mock private TrainingAuditService auditService;
    @Mock private TrainingNotificationService notificationService;

    private final TrainingRecordAggregator aggregator = new TrainingRecordAggregator();
    private TrainingCertificationService service;

    private final UUID reviewerId = UUID.randomUUID();
    private final UUID learnerId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID cohortId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingCertificationService(assignmentRepository, certificationRepository,
                recordRepository, taskRepository, attemptRepository, userRepository, accessService, companyScopeService,
                aggregator, auditService, notificationService);
        TokenContext.set(reviewerId, companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void certify_success_createsConfirmedRecordAndUpdatesStatus() {
        stubScope(TrainingAssignment.STATUS_PENDING_CERTIFICATION);
        when(certificationRepository.findByAssignmentIdAndStatus(assignmentId, TrainingCertification.STATUS_CONFIRMED)).thenReturn(Optional.empty());
        when(recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId))
                .thenReturn(List.of(passedRecord()));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(requiredTask()));
        when(assignmentRepository.updateStatus(assignmentId,
                TrainingAssignment.STATUS_PENDING_CERTIFICATION,
                TrainingAssignment.STATUS_CERTIFIED)).thenReturn(1);
        when(certificationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        TrainingCertificationService.CertificationResult result =
                service.certify(assignmentId, " 已掌握产品知识 ");

        assertEquals(TrainingAssignment.STATUS_CERTIFIED, result.assignment().getStatus());
        ArgumentCaptor<TrainingCertification> captor =
                ArgumentCaptor.forClass(TrainingCertification.class);
        verify(certificationRepository).saveAndFlush(captor.capture());
        TrainingCertification cert = captor.getValue();
        assertEquals(TrainingCertification.STATUS_CONFIRMED, cert.getStatus());
        assertEquals(reviewerId, cert.getConfirmedBy());
        assertEquals(learnerId, cert.getLearnerId());
        assertEquals(companyId, cert.getCompanyId(), "企业归属取分配，不取 token");
        assertEquals("已掌握产品知识", cert.getNote());
    }

    @Test
    void certify_alreadyConfirmed_isIdempotent() {
        TrainingAssignment assignment = assignment(TrainingAssignment.STATUS_CERTIFIED);
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(assignmentRepository.findByIdForUpdate(assignmentId)).thenReturn(Optional.of(assignment));
        when(accessService.assertCanReview(cohortId)).thenReturn(cohort());
        when(certificationRepository.findByAssignmentIdAndStatus(assignmentId, TrainingCertification.STATUS_CONFIRMED))
                .thenReturn(Optional.of(TrainingCertification.builder()
                        .id(UUID.randomUUID()).status(TrainingCertification.STATUS_CONFIRMED)
                        .assignmentId(assignmentId).build()));

        TrainingCertificationService.CertificationResult result =
                service.certify(assignmentId, "重复点击");

        assertEquals(TrainingAssignment.STATUS_CERTIFIED, result.assignment().getStatus());
        verify(certificationRepository, never()).saveAndFlush(any());
        verify(assignmentRepository, never()).updateStatus(any(), anyString(), anyString());
    }

    @Test
    void certify_inProgress_conflicts() {
        stubScope(TrainingAssignment.STATUS_IN_PROGRESS);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.certify(assignmentId, null));

        assertEquals(409, ex.getErrorCode());
        verify(certificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void certify_requiredTaskNotPassed_conflicts() {
        stubScope(TrainingAssignment.STATUS_PENDING_CERTIFICATION);
        when(recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId))
                .thenReturn(List.of());
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(requiredTask()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.certify(assignmentId, null));

        assertEquals(409, ex.getErrorCode());
        verify(certificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void certify_self_forbidden() {
        TokenContext.set(learnerId, companyId);
        stubScope(TrainingAssignment.STATUS_PENDING_CERTIFICATION);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.certify(assignmentId, null));

        assertEquals(403, ex.getErrorCode());
        verify(certificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void certify_selfAsSuperAdmin_allowed() {
        TokenContext.set(learnerId, companyId);
        stubScope(TrainingAssignment.STATUS_PENDING_CERTIFICATION);
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(true);
        when(recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId))
                .thenReturn(List.of(passedRecord()));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(requiredTask()));
        when(assignmentRepository.updateStatus(assignmentId,
                TrainingAssignment.STATUS_PENDING_CERTIFICATION,
                TrainingAssignment.STATUS_CERTIFIED)).thenReturn(1);
        when(certificationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.certify(assignmentId, null);

        verify(certificationRepository).saveAndFlush(any());
    }

    @Test
    void certify_concurrentStatusChange_conflicts() {
        stubScope(TrainingAssignment.STATUS_PENDING_CERTIFICATION);
        when(recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId))
                .thenReturn(List.of(passedRecord()));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(requiredTask()));
        when(assignmentRepository.updateStatus(any(), anyString(), anyString())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.certify(assignmentId, null));

        assertEquals(409, ex.getErrorCode());
    }

    @Test
    void certify_cancelled_conflicts() {
        stubScope(TrainingAssignment.STATUS_CANCELLED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.certify(assignmentId, null));

        assertEquals(409, ex.getErrorCode());
    }

    @Test
    void certify_nonMentorOrCrossCompany_propagatesNotFound() {
        when(assignmentRepository.findById(assignmentId))
                .thenReturn(Optional.of(assignment(TrainingAssignment.STATUS_PENDING_CERTIFICATION)));
        when(accessService.assertCanReview(cohortId))
                .thenThrow(new BusinessException(404, "培训数据不存在或不可访问"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.certify(assignmentId, null));

        assertEquals(404, ex.getErrorCode());
        verify(certificationRepository, never()).saveAndFlush(any());
    }

    // ==================== 撤销认证 ====================

    @Test
    void revoke_confirmedCertification_marksRevokedAndReturnsToPending() {
        stubRevokeScope();
        TrainingCertification confirmed = TrainingCertification.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId)
                .status(TrainingCertification.STATUS_CONFIRMED)
                .confirmedBy(reviewerId).build();
        when(certificationRepository.findByAssignmentIdAndStatus(
                assignmentId, TrainingCertification.STATUS_CONFIRMED))
                .thenReturn(Optional.of(confirmed));
        when(assignmentRepository.revokeCertified(assignmentId)).thenReturn(1);
        when(userRepository.findById(reviewerId))
                .thenReturn(Optional.of(com.aiextract.model.User.builder()
                        .id(reviewerId).name("张主管").build()));

        TrainingCertificationService.RevocationResult result =
                service.revoke(assignmentId, "认证后发现合规项未掌握");

        assertEquals(TrainingAssignment.STATUS_PENDING_CERTIFICATION, result.assignment().getStatus());
        assertEquals(TrainingCertification.STATUS_REVOKED, confirmed.getStatus());
        assertEquals(reviewerId, confirmed.getRevokedBy());
        assertEquals("认证后发现合规项未掌握", confirmed.getRevokeReason());
        assertEquals("张主管", result.revokedByName());
        verify(certificationRepository).save(confirmed);
        verify(assignmentRepository).revokeCertified(assignmentId);
        verify(auditService).record(eq("training.certification.revoke"), anyString(), eq(assignmentId), any());
    }

    @Test
    void revoke_notCertified_conflicts() {
        TrainingAssignment pending = assignment(TrainingAssignment.STATUS_PENDING_CERTIFICATION);
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(pending));
        when(assignmentRepository.findByIdForUpdate(assignmentId)).thenReturn(Optional.of(pending));
        when(accessService.assertCanReview(cohortId)).thenReturn(cohort());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.revoke(assignmentId, "撤销认证的理由"));

        assertEquals("CERT_NOT_CONFIRMED", ex.getSubCode());
        verify(assignmentRepository, never()).revokeCertified(any());
    }

    @Test
    void revoke_self_forbidden() {
        TokenContext.set(learnerId, companyId);
        TrainingAssignment certified = assignment(TrainingAssignment.STATUS_CERTIFIED);
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(certified));
        when(assignmentRepository.findByIdForUpdate(assignmentId)).thenReturn(Optional.of(certified));
        when(accessService.assertCanReview(cohortId)).thenReturn(cohort());
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(false);

        assertEquals(403, assertThrows(BusinessException.class,
                () -> service.revoke(assignmentId, "撤销认证的理由")).getErrorCode());
    }

    @Test
    void revoke_tooShortReason_rejected() {
        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.revoke(assignmentId, "短")).getErrorCode());
        verify(assignmentRepository, never()).findById(any());
    }

    // ==================== 辅助 ====================

    private void stubRevokeScope() {
        TrainingAssignment certified = assignment(TrainingAssignment.STATUS_CERTIFIED);
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(certified));
        when(assignmentRepository.findByIdForUpdate(assignmentId)).thenReturn(Optional.of(certified));
        when(accessService.assertCanReview(cohortId)).thenReturn(cohort());
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(false);
    }

    private void stubScope(String status) {
        when(assignmentRepository.findById(assignmentId))
                .thenReturn(Optional.of(assignment(status)));
        when(assignmentRepository.findByIdForUpdate(assignmentId))
                .thenReturn(Optional.of(assignment(status)));
        when(accessService.assertCanReview(cohortId)).thenReturn(cohort());
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(false);
        // 自我认证等分支在校验前就返回，查询属于可选路径
        lenient().when(certificationRepository.findByAssignmentIdAndStatus(assignmentId, TrainingCertification.STATUS_CONFIRMED))
                .thenReturn(Optional.empty());
    }

    private TrainingAssignment assignment(String status) {
        return TrainingAssignment.builder()
                .id(assignmentId).companyId(companyId).cohortId(cohortId)
                .learnerId(learnerId).status(status)
                .requiredTotal(1).requiredPassed(1).build();
    }

    private TrainingCohort cohort() {
        return TrainingCohort.builder()
                .id(cohortId).companyId(companyId).planId(planId).planVersion(1)
                .name("批次").build();
    }

    private TrainingTaskRecord passedRecord() {
        return TrainingTaskRecord.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId).taskId(taskId)
                .status(TrainingTaskRecord.STATUS_PASSED).build();
    }

    private TrainingTask requiredTask() {
        return TrainingTask.builder()
                .id(taskId).planId(planId).version(1).type(TrainingTask.TYPE_READING)
                .title("必修").phaseNo(1).orderNo(1).isRequired(true).build();
    }
}
