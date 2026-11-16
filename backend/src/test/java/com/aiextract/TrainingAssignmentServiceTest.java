package com.aiextract;

import com.aiextract.config.TokenContext;
import com.aiextract.dto.TrainingBatchAssignRequest;
import com.aiextract.dto.TrainingCohortRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingPlan;
import com.aiextract.model.TrainingPlanVersion;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.model.User;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.TrainingPlanRepository;
import com.aiextract.repository.TrainingPlanVersionRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.TrainingAccessService;
import com.aiextract.service.TrainingAssignmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TrainingAssignmentService 单元测试 —— 批次版本校验、逐人分配结果、任务行初始化、进度统计。
 */
@ExtendWith(MockitoExtension.class)
class TrainingAssignmentServiceTest {

    @Mock
    private TrainingCohortRepository cohortRepository;
    @Mock
    private TrainingAssignmentRepository assignmentRepository;
    @Mock
    private com.aiextract.repository.TrainingAttemptRepository attemptRepository;
    @Mock
    private TrainingTaskRecordRepository taskRecordRepository;
    @Mock
    private TrainingTaskRepository taskRepository;
    @Mock
    private com.aiextract.repository.TrainingCertificationRepository certificationRepository;
    @Mock
    private TrainingPlanRepository planRepository;
    @Mock
    private TrainingPlanVersionRepository versionRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private TrainingAccessService accessService;
    @Mock
    private com.aiextract.config.CompanyScopeService companyScopeService;
    @Mock
    private com.aiextract.service.TrainingNotificationService notificationService;
    @Mock
    private com.aiextract.service.TrainingAuditService auditService;

    private TrainingAssignmentService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();
    private final UUID cohortId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingAssignmentService(cohortRepository, assignmentRepository, attemptRepository,
                taskRecordRepository, taskRepository, certificationRepository, planRepository,
                versionRepository, userRepository, accessService, companyScopeService,
                notificationService, auditService,
                new com.aiextract.service.TrainingRecordAggregator(), objectMapper);
        TokenContext.set(userId, companyId);
        lenient().when(cohortRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(assignmentRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(taskRecordRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accessService.requireCompanyId()).thenReturn(companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    // ==================== 建批次 ====================

    @Test
    void createCohort_publishedVersion_createsActiveCohort() {
        when(planRepository.findByIdAndCompanyId(planId, companyId))
                .thenReturn(Optional.of(plan(TrainingPlan.STATUS_PUBLISHED)));
        when(versionRepository.findByPlanIdAndVersion(planId, 1))
                .thenReturn(Optional.of(version(TrainingPlanVersion.STATUS_PUBLISHED)));

        TrainingCohort cohort = service.createCohort(request());

        assertEquals(TrainingCohort.STATUS_ACTIVE, cohort.getStatus());
        assertEquals(companyId, cohort.getCompanyId());
        assertEquals(1, cohort.getPlanVersion());
    }

    @Test
    void createCohort_draftVersion_conflicts() {
        when(planRepository.findByIdAndCompanyId(planId, companyId))
                .thenReturn(Optional.of(plan(TrainingPlan.STATUS_DRAFT)));
        when(versionRepository.findByPlanIdAndVersion(planId, 1))
                .thenReturn(Optional.of(version(TrainingPlanVersion.STATUS_DRAFT)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createCohort(request()));

        assertEquals(409, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("已发布"));
    }

    @Test
    void createCohort_retiredPlan_conflicts() {
        when(planRepository.findByIdAndCompanyId(planId, companyId))
                .thenReturn(Optional.of(plan(TrainingPlan.STATUS_RETIRED)));

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.createCohort(request())).getErrorCode());
    }

    @Test
    void createCohort_mentorFromAnotherCompany_rejected() {
        when(planRepository.findByIdAndCompanyId(planId, companyId))
                .thenReturn(Optional.of(plan(TrainingPlan.STATUS_PUBLISHED)));
        when(versionRepository.findByPlanIdAndVersion(planId, 1))
                .thenReturn(Optional.of(version(TrainingPlanVersion.STATUS_PUBLISHED)));
        UUID mentorId = UUID.randomUUID();
        when(userRepository.findById(mentorId))
                .thenReturn(Optional.of(user(mentorId, UUID.randomUUID(), true)));
        TrainingCohortRequest req = request();
        req.setMentorId(mentorId);

        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.createCohort(req)).getErrorCode());
    }

    // ==================== 批量分配 ====================

    @Test
    void batchAssign_returnsPerLearnerResultsAndInitializesRecords() throws Exception {
        stubAssignableCohort();
        UUID first = UUID.randomUUID();
        UUID already = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();

        TrainingTask t1 = task("阅读一", "[]");
        TrainingTask t2 = task("考试一", objectMapper.writeValueAsString(List.of(t1.getId().toString())));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(t1, t2));
        when(userRepository.findAllById(anyList())).thenReturn(List.of(
                user(first, companyId, true),
                user(already, companyId, true),
                user(inactive, companyId, false),
                user(foreign, UUID.randomUUID(), true)));
        when(assignmentRepository.findExistingLearnerIds(eq(cohortId), anyList()))
                .thenReturn(List.of(already));

        TrainingAssignmentService.BatchResult result = service.batchAssign(
                TrainingBatchAssignRequest.builder()
                        .cohortId(cohortId)
                        .learnerIds(List.of(first, already, inactive, foreign, unknown))
                        .build());

        assertEquals(1, result.assigned());
        assertEquals(2, result.skipped(), "重复分配与停用账号都算跳过");
        assertEquals(2, result.failed(), "跨企业与不存在用户算失败");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TrainingAssignment>> aCaptor = ArgumentCaptor.forClass(List.class);
        verify(assignmentRepository).saveAll(aCaptor.capture());
        assertEquals(1, aCaptor.getValue().size());
        TrainingAssignment saved = aCaptor.getValue().get(0);
        assertEquals(2, saved.getTotalTasks());
        assertEquals(2, saved.getRequiredTotal());
        assertEquals(TrainingAssignment.STATUS_NOT_STARTED, saved.getStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TrainingTaskRecord>> rCaptor = ArgumentCaptor.forClass(List.class);
        verify(taskRecordRepository).saveAll(rCaptor.capture());
        List<TrainingTaskRecord> records = rCaptor.getValue();
        assertEquals(2, records.size());
        assertEquals(TrainingTaskRecord.STATUS_AVAILABLE, records.get(0).getStatus());
        assertEquals(TrainingTaskRecord.STATUS_LOCKED, records.get(1).getStatus(),
                "有前置的任务初始锁定");
    }

    @Test
    void batchAssign_inactiveCohort_conflicts() {
        TrainingCohort cohort = cohort(TrainingCohort.STATUS_COMPLETED);
        when(cohortRepository.findByIdForUpdate(cohortId)).thenReturn(Optional.of(cohort));

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.batchAssign(batch())).getErrorCode());
        verify(assignmentRepository, never()).saveAll(anyList());
    }

    @Test
    void batchAssign_noTasks_rejected() {
        stubAssignableCohort();
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of());

        assertEquals(400, assertThrows(BusinessException.class,
                () -> service.batchAssign(batch())).getErrorCode());
    }

    // ==================== 取消 ====================

    @Test
    void cancelAssignment_certified_conflicts() {
        UUID assignmentId = UUID.randomUUID();
        when(assignmentRepository.findByIdAndCompanyId(assignmentId, companyId))
                .thenReturn(Optional.of(TrainingAssignment.builder()
                        .id(assignmentId).companyId(companyId).learnerId(userId)
                        .status(TrainingAssignment.STATUS_CERTIFIED).build()));

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.cancelAssignment(assignmentId)).getErrorCode());
    }

    @Test
    void cancelAssignment_open_setsCancelled() {
        UUID assignmentId = UUID.randomUUID();
        TrainingAssignment assignment = TrainingAssignment.builder()
                .id(assignmentId).companyId(companyId).learnerId(userId)
                .status(TrainingAssignment.STATUS_IN_PROGRESS).build();
        when(assignmentRepository.findByIdAndCompanyId(assignmentId, companyId))
                .thenReturn(Optional.of(assignment));
        when(assignmentRepository.cancelIfOpen(assignmentId)).thenReturn(1);

        TrainingAssignment result = service.cancelAssignment(assignmentId);

        assertEquals(TrainingAssignment.STATUS_CANCELLED, result.getStatus());
    }

    // ==================== 进度 ====================

    @Test
    void cohortProgress_countsStatusesAndOverdue() {
        TrainingCohort cohort = cohort(TrainingCohort.STATUS_ACTIVE);
        when(accessService.assertCanReview(cohortId)).thenReturn(cohort);

        UUID learnerA = UUID.randomUUID();
        UUID learnerB = UUID.randomUUID();
        TrainingAssignment a = TrainingAssignment.builder()
                .id(UUID.randomUUID()).cohortId(cohortId).learnerId(learnerA)
                .status(TrainingAssignment.STATUS_IN_PROGRESS)
                .totalTasks(5).requiredTotal(3).completedTasks(2).requiredPassed(1).passedTasks(1)
                .deadline(LocalDateTime.now().minusDays(1)).build();
        TrainingAssignment b = TrainingAssignment.builder()
                .id(UUID.randomUUID()).cohortId(cohortId).learnerId(learnerB)
                .status(TrainingAssignment.STATUS_CERTIFIED)
                .totalTasks(5).requiredTotal(3).completedTasks(5).requiredPassed(3).passedTasks(5)
                .deadline(LocalDateTime.now().minusDays(1)).build();
        when(assignmentRepository.findByCohortIdOrderByCreatedAtAscIdAsc(eq(cohortId), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(a, b)));
        when(assignmentRepository.cohortStatusCounts(cohortId))
                .thenReturn(List.<Object[]>of(new Object[]{2L, 0L, 1L, 0L, 1L, 0L}));
        when(assignmentRepository.countOverdueByCohort(cohortId)).thenReturn(1L);
        when(assignmentRepository.countPendingReviewByCohort(cohortId)).thenReturn(0L);
        when(assignmentRepository.countStalePendingReviewByCohort(eq(cohortId), any())).thenReturn(0L);
        when(attemptRepository.findOldestPendingSubmitByAssignmentIds(anyList())).thenReturn(List.of());
        when(userRepository.findAllById(anyList())).thenReturn(List.of(
                User.builder().id(learnerA).name("小李").build(),
                User.builder().id(learnerB).name("小王").build()));

        TrainingAssignmentService.CohortProgress progress = service.cohortProgress(cohortId);

        assertEquals(2, progress.learners().size());
        assertEquals("小李", progress.learners().get(0).name());
        assertTrue(progress.learners().get(0).overdue(), "已过期且未认证 → overdue");
        assertFalse(progress.learners().get(1).overdue(), "已认证不算逾期");
        Map<String, Object> summary = progress.summary();
        assertEquals(2L, summary.get("total"));
        assertEquals(1L, summary.get("inProgress"));
        assertEquals(1L, summary.get("certified"));
        assertEquals(1L, summary.get("overdue"));
    }

    @Test
    void cohortProgress_pendingCertification_exposesBlockers() {
        TrainingCohort cohort = cohort(TrainingCohort.STATUS_ACTIVE);
        when(accessService.assertCanReview(cohortId)).thenReturn(cohort);

        UUID learnerA = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        TrainingAssignment a = TrainingAssignment.builder()
                .id(assignmentId).cohortId(cohortId).learnerId(learnerA)
                .status(TrainingAssignment.STATUS_PENDING_CERTIFICATION)
                .totalTasks(2).requiredTotal(2).completedTasks(2).requiredPassed(1)
                .passedTasks(1).build();
        when(assignmentRepository.findByCohortIdOrderByCreatedAtAscIdAsc(eq(cohortId), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(a)));
        when(assignmentRepository.cohortStatusCounts(cohortId))
                .thenReturn(List.<Object[]>of(new Object[]{1L, 0L, 0L, 1L, 0L, 0L}));
        when(assignmentRepository.countOverdueByCohort(cohortId)).thenReturn(0L);
        when(assignmentRepository.countPendingReviewByCohort(cohortId)).thenReturn(0L);
        when(assignmentRepository.countStalePendingReviewByCohort(eq(cohortId), any())).thenReturn(0L);
        when(attemptRepository.findOldestPendingSubmitByAssignmentIds(anyList())).thenReturn(List.of());
        when(userRepository.findAllById(anyList())).thenReturn(List.of(
                User.builder().id(learnerA).name("小李").build()));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(
                        TrainingTask.builder().id(t1).planId(planId).version(1)
                                .type(TrainingTask.TYPE_READING).title("必修一")
                                .phaseNo(1).orderNo(1).isRequired(true).build(),
                        TrainingTask.builder().id(t2).planId(planId).version(1)
                                .type(TrainingTask.TYPE_EXAM).title("必修二")
                                .phaseNo(1).orderNo(2).isRequired(true).build()));
        when(taskRecordRepository.findByAssignmentIdIn(List.of(assignmentId)))
                .thenReturn(List.of(
                        TrainingTaskRecord.builder().id(UUID.randomUUID())
                                .assignmentId(assignmentId).taskId(t1)
                                .status(TrainingTaskRecord.STATUS_PASSED).build(),
                        TrainingTaskRecord.builder().id(UUID.randomUUID())
                                .assignmentId(assignmentId).taskId(t2)
                                .status(TrainingTaskRecord.STATUS_FAILED).build()));

        TrainingAssignmentService.CohortProgress progress = service.cohortProgress(cohortId);

        TrainingAssignmentService.LearnerProgress row = progress.learners().get(0);
        assertFalse(row.canCertify(), "必修未全过不能认证");
        assertEquals("required_not_passed", row.certifyBlockedReason());
        assertEquals(1, row.certificationBlockers().size());
        assertEquals("必修二", row.certificationBlockers().get(0).title());
        assertEquals("failed", row.certificationBlockers().get(0).recordStatus());
    }

    @Test
    void myAssignments_queriesOnlyCurrentUser() {
        when(assignmentRepository.findByLearnerIdAndCompanyIdOrderByCreatedAtDesc(
                eq(userId), eq(companyId), any()))
                .thenReturn(org.springframework.data.domain.Page.<TrainingAssignment>empty());

        TrainingAssignmentService.MyAssignments result = service.myAssignments(1, 20);

        assertTrue(result.page().getContent().isEmpty());
        assertEquals(Map.of(), result.cohorts());
    }

    // ==================== 辅助 ====================

    private void stubAssignableCohort() {
        when(cohortRepository.findByIdForUpdate(cohortId))
                .thenReturn(Optional.of(cohort(TrainingCohort.STATUS_ACTIVE)));
    }

    private TrainingCohort cohort(String status) {
        return TrainingCohort.builder()
                .id(cohortId).companyId(companyId).planId(planId).planVersion(1)
                .name("一期").status(status).build();
    }

    private TrainingCohortRequest request() {
        return TrainingCohortRequest.builder()
                .planId(planId).planVersion(1).name("一期").build();
    }

    private TrainingBatchAssignRequest batch() {
        return TrainingBatchAssignRequest.builder()
                .cohortId(cohortId).learnerIds(List.of(UUID.randomUUID())).build();
    }

    private TrainingPlan plan(String status) {
        return TrainingPlan.builder().id(planId).companyId(companyId).name("计划").status(status).build();
    }

    private TrainingPlanVersion version(String status) {
        return TrainingPlanVersion.builder()
                .id(UUID.randomUUID()).planId(planId).version(1).status(status).build();
    }

    private TrainingTask task(String title, String prerequisites) {
        return TrainingTask.builder()
                .id(UUID.randomUUID()).planId(planId).version(1)
                .type(TrainingTask.TYPE_READING).title(title)
                .phaseNo(1).orderNo(1).isRequired(true)
                .prerequisites(prerequisites)
                .build();
    }

    private User user(UUID id, UUID ownerCompanyId, boolean active) {
        return User.builder()
                .id(id).companyId(ownerCompanyId).name("学员-" + id.toString().substring(0, 4))
                .isActive(active).role("employee")
                .build();
    }
}
