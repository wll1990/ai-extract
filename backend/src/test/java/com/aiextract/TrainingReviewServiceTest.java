package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.TrainingReviewRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingReview;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.model.User;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingReviewRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.TrainingAccessService;
import com.aiextract.service.TrainingProgressService;
import com.aiextract.service.TrainingReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TrainingReviewService 单元测试 —— 归属链、自我复核、状态守卫、定稿回写。
 */
@ExtendWith(MockitoExtension.class)
class TrainingReviewServiceTest {

    @Mock private TrainingAccessService accessService;
    @Mock private TrainingAttemptRepository attemptRepository;
    @Mock private TrainingTaskRecordRepository recordRepository;
    @Mock private TrainingAssignmentRepository assignmentRepository;
    @Mock private TrainingTaskRepository taskRepository;
    @Mock private TrainingReviewRepository reviewRepository;
    @Mock private com.aiextract.repository.TrainingCertificationRepository certificationRepository;
    @Mock private com.aiextract.repository.SkillMessageRepository messageRepository;
    @Mock private UserRepository userRepository;
    @Mock private TrainingProgressService progressService;
    @Mock private CompanyScopeService companyScopeService;
    @Mock private com.aiextract.service.TrainingNotificationService notificationService;

    private TrainingReviewService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID reviewerId = UUID.randomUUID();
    private final UUID learnerId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID cohortId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID attemptId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingReviewService(accessService, attemptRepository, recordRepository,
                assignmentRepository, taskRepository, reviewRepository, certificationRepository, messageRepository,
                userRepository, progressService, companyScopeService, notificationService, objectMapper);
        TokenContext.set(reviewerId, companyId);
        lenient().when(certificationRepository.findByAssignmentIdOrderByCreatedAtDesc(any()))
                .thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void review_pass_finalizesAndUpdatesProgress() {
        stubScope();
        TrainingAttempt attempt = attempt(TrainingAttempt.STATUS_PENDING_REVIEW);
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(attempt));
        when(attemptRepository.finalizeByReview(eq(attemptId),
                eq(TrainingAttempt.STATUS_PENDING_REVIEW), eq(TrainingAttempt.STATUS_FINALIZED),
                eq(TrainingAttempt.RESULT_PASS), any())).thenReturn(1);
        when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TrainingReviewService.ReviewResult result = service.review(attemptId,
                request(TrainingReview.ACTION_PASS, "表现达标"));

        assertEquals(TrainingAttempt.STATUS_FINALIZED, result.attempt().getStatus());
        assertEquals(TrainingAttempt.RESULT_PASS, result.attempt().getResult());
        verify(progressService).applyRecordResult(recordId, true);

        ArgumentCaptor<TrainingReview> captor = ArgumentCaptor.forClass(TrainingReview.class);
        verify(reviewRepository).save(captor.capture());
        assertEquals(reviewerId, captor.getValue().getReviewerId());
        assertEquals("{}", captor.getValue().getOriginalScore(), "原分必须保留");
    }

    @Test
    void review_reject_appliesFail() {
        stubScope();
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_PENDING_REVIEW)));
        when(attemptRepository.finalizeByReview(eq(attemptId),
                eq(TrainingAttempt.STATUS_PENDING_REVIEW), eq(TrainingAttempt.STATUS_FINALIZED),
                eq(TrainingAttempt.RESULT_FAIL), any())).thenReturn(1);
        when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TrainingReviewService.ReviewResult result = service.review(attemptId,
                request(TrainingReview.ACTION_REJECT, "首轮话术未覆盖异议处理"));

        assertEquals(TrainingAttempt.RESULT_FAIL, result.attempt().getResult());
        verify(progressService).applyRecordResult(recordId, false);
    }

    @Test
    void review_selfReview_forbidden() {
        TokenContext.set(learnerId, companyId);
        stubScope();
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_PENDING_REVIEW)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.review(attemptId, request(TrainingReview.ACTION_PASS, "自己判自己")));

        assertEquals(403, ex.getErrorCode());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void review_selfReview_superAdminAllowed() {
        TokenContext.set(learnerId, companyId);
        stubScope();
        when(companyScopeService.isUnrestrictedDataScope()).thenReturn(true);
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_PENDING_REVIEW)));
        when(attemptRepository.finalizeByReview(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.review(attemptId, request(TrainingReview.ACTION_PASS, "超管复核"));

        verify(reviewRepository).save(any());
    }

    @Test
    void review_nonMentorOrCrossCompany_isNotFound() {
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_PENDING_REVIEW)));
        when(recordRepository.findById(recordId)).thenReturn(Optional.of(record()));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment()));
        when(accessService.assertCanReview(cohortId))
                .thenThrow(new BusinessException(404, "培训批次不存在或不可访问"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.review(attemptId, request(TrainingReview.ACTION_PASS, "越权")));

        assertEquals(404, ex.getErrorCode());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void review_errorAttempt_allowsHumanDecision() {
        stubScope();
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_ERROR)));
        when(attemptRepository.finalizeByReview(eq(attemptId),
                eq(TrainingAttempt.STATUS_ERROR), eq(TrainingAttempt.STATUS_FINALIZED),
                eq(TrainingAttempt.RESULT_PASS), any())).thenReturn(1);
        when(reviewRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TrainingReviewService.ReviewResult result = service.review(attemptId,
                request(TrainingReview.ACTION_PASS, "人工核对证据后通过"));

        assertEquals(TrainingAttempt.STATUS_FINALIZED, result.attempt().getStatus());
        assertEquals(TrainingAttempt.RESULT_PASS, result.attempt().getResult());
        verify(progressService).applyRecordResult(recordId, true);
    }

    @Test
    void review_onCancelledAssignment_conflicts() {
        stubScope();
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_PENDING_REVIEW)));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId).companyId(companyId)
                        .cohortId(cohortId).learnerId(learnerId)
                        .status(TrainingAssignment.STATUS_CANCELLED).build()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.review(attemptId, request(TrainingReview.ACTION_PASS, "取消后复核")));

        assertEquals(409, ex.getErrorCode());
        verify(reviewRepository, never()).save(any());
    }

    @Test
    void review_notPending_conflicts() {
        stubScope();
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_FINALIZED)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.review(attemptId, request(TrainingReview.ACTION_PASS, "事后改分")));

        assertEquals(409, ex.getErrorCode());
        verify(reviewRepository, never()).save(any());
        verify(progressService, never()).applyRecordResult(any(), eq(true));
    }

    @Test
    void review_concurrentFinalize_conflictsWithoutSideEffects() {
        stubScope();
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_PENDING_REVIEW)));
        when(attemptRepository.finalizeByReview(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.review(attemptId, request(TrainingReview.ACTION_PASS, "并发复核")));

        assertEquals(409, ex.getErrorCode());
        verify(reviewRepository, never()).save(any());
        verify(progressService, never()).applyRecordResult(any(), any(Boolean.class));
    }

    @Test
    void review_invalidAction_rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.review(attemptId, request("approve", "动作写错")));

        assertEquals(400, ex.getErrorCode());
        verify(attemptRepository, never()).findById(any());
    }

    @Test
    void getAttemptDetail_returnsGoldAndReviewsForAuthorizedReviewer() {
        stubScope();
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_PENDING_REVIEW)));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task()));
        when(userRepository.findById(learnerId)).thenReturn(Optional.of(
                User.builder().id(learnerId).name("新人甲").build()));
        when(reviewRepository.findByAttemptIdOrderByCreatedAtAsc(attemptId))
                .thenReturn(List.of(TrainingReview.builder().id(UUID.randomUUID())
                        .attemptId(attemptId).action("pass").reason("历史").build()));

        TrainingReviewService.AdminAttemptDetail detail = service.getAttemptDetail(attemptId);

        assertEquals(learnerId, detail.assignment().getLearnerId());
        assertEquals(1, detail.reviews().size());
        assertEquals("新人甲", detail.learner().getName());
    }

    @Test
    void listAssignmentAttempts_groupsByTaskRecordAndChecksScope() {
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment()));
        when(accessService.assertCanReview(cohortId)).thenReturn(cohort());
        TrainingTaskRecord first = record();
        TrainingTaskRecord second = TrainingTaskRecord.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId).taskId(UUID.randomUUID())
                .status(TrainingTaskRecord.STATUS_AVAILABLE).build();
        when(recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId))
                .thenReturn(List.of(second, first));
        TrainingAttempt older = attempt(TrainingAttempt.STATUS_FINALIZED);
        TrainingAttempt newer = TrainingAttempt.builder()
                .id(UUID.randomUUID()).taskRecordId(recordId).attemptNo(2)
                .status(TrainingAttempt.STATUS_PENDING_REVIEW).build();
        when(attemptRepository.findByTaskRecordIdInOrderByAttemptNoDesc(any()))
                .thenReturn(List.of(newer, older));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(any(), any()))
                .thenReturn(List.of(task()));

        TrainingReviewService.AssignmentAttempts result = service.listAssignmentAttempts(assignmentId);

        assertEquals(2, result.items().size());
        TrainingReviewService.TaskAttempts grouped = result.items().get(0);
        assertEquals(recordId, grouped.record().getId());
        assertEquals(2, grouped.attempts().size());
        assertEquals(2, grouped.attempts().get(0).getAttemptNo(), "尝试按次数倒序");
        verify(accessService).assertCanReview(cohortId);
    }

    @Test
    void getAttemptDetail_inProgress_doesNotExposeAnswerKey() {
        stubScope();
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_IN_PROGRESS)));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task()));

        TrainingReviewService.AdminAttemptDetail detail = service.getAttemptDetail(attemptId);

        assertFalse(detail.exposeAnswerKey(), "考试进行中不下发金标");
    }

    @Test
    void getAttemptDetail_selfLearner_notFound() {
        TokenContext.set(learnerId, companyId);
        stubScope();
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_PENDING_REVIEW)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.getAttemptDetail(attemptId));

        assertEquals(404, ex.getErrorCode(), "学员不能通过管理接口看自己的金标");
    }

    @Test
    void regrade_onlyAcceptsErrorAttempts() {
        stubScope();
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_ERROR)));
        when(attemptRepository.resetForRegrade(attemptId)).thenReturn(1);

        TrainingAttempt result = service.regrade(attemptId);

        assertEquals(TrainingAttempt.STATUS_SUBMITTED, result.getStatus());
        assertEquals(0, result.getGradingAttempts());
        verify(attemptRepository).resetForRegrade(attemptId);
    }

    @Test
    void regrade_finalizedAttempt_conflicts() {
        stubScope();
        when(attemptRepository.findById(attemptId))
                .thenReturn(Optional.of(attempt(TrainingAttempt.STATUS_FINALIZED)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.regrade(attemptId));

        assertEquals(409, ex.getErrorCode());
        verify(attemptRepository, never()).resetForRegrade(any());
    }

    @Test
    void review_rejectWithTooShortReason_rejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.review(attemptId, request(TrainingReview.ACTION_REJECT, "x")));

        assertEquals(400, ex.getErrorCode());
        verify(attemptRepository, never()).findById(any());
    }

    // ==================== 辅助 ====================

    private void stubScope() {
        when(recordRepository.findById(recordId)).thenReturn(Optional.of(record()));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment()));
        when(accessService.assertCanReview(cohortId)).thenReturn(cohort());
        // 详情/列表接口不走到自我复核判定，这里用 lenient 避免严格桩误报
        lenient().when(companyScopeService.isUnrestrictedDataScope()).thenReturn(false);
    }

    private TrainingAttempt attempt(String status) {
        return TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskId(taskId).taskRecordId(recordId)
                .type(TrainingAttempt.TYPE_EXAM).attemptNo(1).status(status)
                .result(TrainingAttempt.RESULT_PASS).score("{}").build();
    }

    private TrainingTaskRecord record() {
        return TrainingTaskRecord.builder()
                .id(recordId).assignmentId(assignmentId).taskId(taskId)
                .status(TrainingTaskRecord.STATUS_PENDING_REVIEW).build();
    }

    private TrainingAssignment assignment() {
        return TrainingAssignment.builder()
                .id(assignmentId).companyId(companyId).cohortId(cohortId)
                .learnerId(learnerId).status(TrainingAssignment.STATUS_PENDING_CERTIFICATION)
                .build();
    }

    private TrainingCohort cohort() {
        return TrainingCohort.builder()
                .id(cohortId).companyId(companyId).planId(UUID.randomUUID())
                .planVersion(1).name("批次").build();
    }

    private TrainingTask task() {
        return TrainingTask.builder()
                .id(taskId).planId(UUID.randomUUID()).version(1).phaseNo(1).orderNo(1)
                .type(TrainingTask.TYPE_EXAM).title("考试").build();
    }

    private TrainingReviewRequest request(String action, String reason) {
        return TrainingReviewRequest.builder().action(action).reason(reason).build();
    }
}
