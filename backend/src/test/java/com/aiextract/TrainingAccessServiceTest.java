package com.aiextract;

import com.aiextract.config.Permission;
import com.aiextract.config.RolePermissions;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingCohort;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.service.TrainingAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * TrainingAccessService 单元测试 —— 跨企业 404、导师越权 403、学员归属。
 */
@ExtendWith(MockitoExtension.class)
class TrainingAccessServiceTest {

    @Mock
    private TrainingCohortRepository cohortRepository;
    @Mock
    private TrainingAssignmentRepository assignmentRepository;
    @Mock
    private com.aiextract.repository.TrainingTaskRecordRepository taskRecordRepository;
    @Mock
    private com.aiextract.repository.TrainingAttemptRepository attemptRepository;

    private TrainingAccessService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID otherCompanyId = UUID.randomUUID();
    private final UUID cohortId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingAccessService(cohortRepository, assignmentRepository,
                taskRecordRepository, attemptRepository);
        TokenContext.set(userId, companyId);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void isAdmin_dependsOnTrainingManageAuthority() {
        authenticate(RolePermissions.EMPLOYEE, Permission.TRAINING_REVIEW);
        assertFalse(service.isAdmin());

        authenticate(RolePermissions.COMPANY_ADMIN, Permission.TRAINING_MANAGE);
        assertTrue(service.isAdmin());

        SecurityContextHolder.clearContext();
        assertFalse(service.isAdmin());
    }

    @Test
    void crossCompanyCohort_is404NotForbidden() {
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort(otherCompanyId, null)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireCohort(cohortId));

        assertEquals(404, ex.getErrorCode(), "跨企业必须用 404，避免泄露 UUID 归属");
    }

    @Test
    void missingCohort_is404() {
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.empty());

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.requireCohort(cohortId)).getErrorCode());
    }

    @Test
    void admin_canReviewAnyCohortInCompany() {
        authenticate(RolePermissions.COMPANY_ADMIN, Permission.TRAINING_MANAGE);
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort(companyId, UUID.randomUUID())));

        assertEquals(cohortId, service.assertCanReview(cohortId).getId());
    }

    @Test
    void mentor_canReviewOwnCohort() {
        authenticate(RolePermissions.EMPLOYEE, Permission.TRAINING_REVIEW);
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort(companyId, userId)));

        assertEquals(cohortId, service.assertCanReview(cohortId).getId());
    }

    @Test
    void nonMentorEmployee_cannotReviewOthersCohort() {
        authenticate(RolePermissions.EMPLOYEE, Permission.TRAINING_REVIEW);
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(cohort(companyId, UUID.randomUUID())));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertCanReview(cohortId));

        // 与「不存在」统一返回 404，避免用 403 泄露批次存在性
        assertEquals(404, ex.getErrorCode());
    }

    @Test
    void learnerAssignment_ofOtherLearner_is404() {
        UUID assignmentId = UUID.randomUUID();
        TrainingAssignment other = TrainingAssignment.builder()
                .id(assignmentId).companyId(companyId).learnerId(UUID.randomUUID()).build();
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(other));

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.requireLearnerAssignment(assignmentId)).getErrorCode());
    }

    @Test
    void learnerAssignment_ofSelf_passes() {
        UUID assignmentId = UUID.randomUUID();
        TrainingAssignment own = TrainingAssignment.builder()
                .id(assignmentId).companyId(companyId).learnerId(userId).build();
        lenient().when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(own));

        assertEquals(assignmentId, service.requireLearnerAssignment(assignmentId).getId());
    }

    @Test
    void learnerAttempt_ofOtherLearner_is404() {
        UUID attemptId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(
                com.aiextract.model.TrainingAttempt.builder()
                        .id(attemptId).assignmentId(assignmentId).build()));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId).companyId(companyId)
                        .learnerId(UUID.randomUUID()).build()));

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.requireLearnerAttempt(attemptId)).getErrorCode());
    }

    @Test
    void learnerAttempt_crossCompany_is404() {
        UUID attemptId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(
                com.aiextract.model.TrainingAttempt.builder()
                        .id(attemptId).assignmentId(assignmentId).build()));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId).companyId(otherCompanyId)
                        .learnerId(userId).build()));

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.requireLearnerAttempt(attemptId)).getErrorCode());
    }

    @Test
    void learnerAttempt_ofSelf_passes() {
        UUID attemptId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(
                com.aiextract.model.TrainingAttempt.builder()
                        .id(attemptId).assignmentId(assignmentId).build()));
        lenient().when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId).companyId(companyId)
                        .learnerId(userId).build()));

        assertEquals(attemptId, service.requireLearnerAttempt(attemptId).getId());
    }

    @Test
    void learnerRecord_ofOtherLearner_is404() {
        UUID recordId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        when(taskRecordRepository.findById(recordId)).thenReturn(Optional.of(
                com.aiextract.model.TrainingTaskRecord.builder()
                        .id(recordId).assignmentId(assignmentId).build()));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId).companyId(companyId)
                        .learnerId(UUID.randomUUID()).build()));

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.requireLearnerRecord(recordId)).getErrorCode());
    }

    // ==================== 辅助 ====================

    private void authenticate(String role, String permission) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null,
                        List.of(new SimpleGrantedAuthority(role), new SimpleGrantedAuthority(permission))));
    }

    private TrainingCohort cohort(UUID ownerCompanyId, UUID mentorId) {
        return TrainingCohort.builder()
                .id(cohortId).companyId(ownerCompanyId).mentorId(mentorId)
                .planId(UUID.randomUUID()).planVersion(1)
                .name("批次").status(TrainingCohort.STATUS_ACTIVE)
                .build();
    }
}
