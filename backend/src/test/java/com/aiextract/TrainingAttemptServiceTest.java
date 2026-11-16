package com.aiextract;

import com.aiextract.config.PromptLoader;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.PracticeRespondRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.SkillConversationRepository;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingReviewRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.service.ChatStreamAdapter;
import com.aiextract.service.ChatStreamService;
import com.aiextract.service.TrainingAccessService;
import com.aiextract.service.TrainingAttemptService;
import com.aiextract.service.TrainingProgressService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TrainingAttemptService 单元测试 —— 考试冻结题面、金标不下发、续接、次数上限、
 * 答题锁定、考试禁提示、陪练上下文绑定。
 */
@ExtendWith(MockitoExtension.class)
class TrainingAttemptServiceTest {

    @Mock private TrainingAccessService accessService;
    @Mock private com.aiextract.config.CompanyScopeService companyScopeService;
    @Mock private TrainingTaskRecordRepository recordRepository;
    @Mock private TrainingAssignmentRepository assignmentRepository;
    @Mock private TrainingAttemptRepository attemptRepository;
    @Mock private TrainingReviewRepository reviewRepository;
    @Mock private TrainingTaskRepository taskRepository;
    @Mock private EvaluationItemRepository itemRepository;
    @Mock private SkillConversationRepository conversationRepository;
    @Mock private com.aiextract.repository.SkillMessageRepository messageRepository;
    @Mock private com.aiextract.service.TrainingRateLimiter rateLimiter;
    @Mock private TrainingProgressService progressService;
    @Mock private ChatStreamService chatStreamService;
    @Mock private ChatStreamAdapter chatStreamAdapter;
    @Mock private PromptLoader promptLoader;

    private TrainingAttemptService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingAttemptService(accessService, companyScopeService, recordRepository, assignmentRepository,
                attemptRepository, reviewRepository, taskRepository, itemRepository, conversationRepository,
                messageRepository, rateLimiter, progressService, chatStreamService, chatStreamAdapter,
                promptLoader, objectMapper);
        TokenContext.set(userId, companyId);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "maxPracticeMessages", 40);
        lenient().when(attemptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(attemptRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(accessService.requireLearnerRecord(recordId)).thenReturn(record(TrainingTaskRecord.STATUS_AVAILABLE));
        // 开始尝试前会锁进度行：默认返回 available，个别用例覆盖
        lenient().when(recordRepository.findByIdForUpdate(recordId))
                .thenReturn(Optional.of(record(TrainingTaskRecord.STATUS_AVAILABLE)));
        // 发言额度默认放行；个别用例覆盖为 false 验证 429
        lenient().when(rateLimiter.tryConsumePracticeMessage(any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void startAttempt_readingTask_rejected() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(
                task(TrainingTask.TYPE_READING, true, null)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startAttempt(recordId));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("标记已读"));
    }

    @Test
    void startAttempt_drillWithUnpublishedSkill_blockedWithoutCreatingAttempt() {
        TrainingTask drill = task(TrainingTask.TYPE_DRILL, false, null);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(drill));
        org.mockito.Mockito.doThrow(new BusinessException(400, "引用的分身未发布"))
                .when(companyScopeService).assertSkillUsable(drill.getResourceId(), drill.getTitle());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startAttempt(recordId));

        assertEquals(400, ex.getErrorCode());
        verify(attemptRepository, never()).save(any());
    }

    @Test
    void startAttempt_oldRemediationRound_conflicts() {
        TrainingTaskRecord oldRound = record(TrainingTaskRecord.STATUS_FAILED);
        oldRound.setRoundNo(1);
        when(recordRepository.findByIdForUpdate(recordId)).thenReturn(Optional.of(oldRound));
        when(recordRepository.findMaxRoundNo(assignmentId, taskId)).thenReturn(2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startAttempt(recordId));

        assertEquals(409, ex.getErrorCode());
        assertEquals("TRAINING_OLD_ROUND", ex.getSubCode());
        verify(attemptRepository, never()).save(any());
    }

    @Test
    void startAttempt_maxAttemptsReached_conflicts() {
        stubAttemptContext(TrainingTask.TYPE_PRACTICE, true, 1, null);
        when(attemptRepository.countByTaskRecordIdAndStatusIn(eq(recordId), any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startAttempt(recordId));

        assertEquals(409, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("最大尝试次数"));
        verify(attemptRepository, never()).save(any());
    }

    @Test
    void startAttempt_resumesOngoingWithoutCreatingNew() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(
                task(TrainingTask.TYPE_PRACTICE, true, null)));
        stubAssignmentAndDeadline(null);
        TrainingAttempt ongoing = TrainingAttempt.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId).taskId(taskId)
                .taskRecordId(recordId).type(TrainingAttempt.TYPE_PRACTICE)
                .status(TrainingAttempt.STATUS_IN_PROGRESS).attemptNo(1)
                .skillId(UUID.randomUUID()).conversationId(UUID.randomUUID())
                .build();
        when(attemptRepository.findFirstByTaskRecordIdAndStatusOrderByAttemptNoDesc(
                recordId, TrainingAttempt.STATUS_IN_PROGRESS)).thenReturn(Optional.of(ongoing));

        TrainingAttemptService.AttemptView view = service.startAttempt(recordId);

        assertEquals(ongoing.getId(), view.attempt().getId());
        verify(attemptRepository, never()).save(any());
    }

    @Test
    void startAttempt_exam_snapshotKeepsAnswerKeyServerSideOnly() {
        UUID datasetId = UUID.randomUUID();
        TrainingTask exam = task(TrainingTask.TYPE_EXAM, false, 60);
        exam.setExamDatasetId(datasetId);
        exam.setExamDatasetVersion(1);
        exam.setExamItemCount(2);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(exam));
        stubAssignmentAndDeadline(null);
        when(attemptRepository.findFirstByTaskRecordIdAndStatusOrderByAttemptNoDesc(
                recordId, TrainingAttempt.STATUS_IN_PROGRESS)).thenReturn(Optional.empty());
        when(attemptRepository.countByTaskRecordIdAndStatusIn(eq(recordId), any())).thenReturn(0L);
        when(attemptRepository.findMaxAttemptNo(recordId)).thenReturn(0);
        when(itemRepository.findByDatasetIdAndVersionAndStatus(datasetId, 1, EvaluationItem.STATUS_ACTIVE))
                .thenReturn(List.of(item("题目一"), item("题目二")));

        TrainingAttemptService.AttemptView view = service.startAttempt(recordId);

        JsonNode questions = view.questions();
        assertEquals(2, questions.size());
        assertFalse(questions.get(0).has("expectedPoints"), "学员视图不得包含金标");
        assertFalse(questions.get(0).has("answerKey"));
        assertTrue(view.attempt().getQuestionSnapshot().contains("answerKey"),
                "快照里保留金标，仅服务端使用");
        assertEquals(TrainingAttempt.TYPE_EXAM, view.attempt().getType());
    }

    @Test
    void startAttempt_exam_deadlineCappedByAssignmentDeadline() {
        UUID datasetId = UUID.randomUUID();
        TrainingTask exam = task(TrainingTask.TYPE_EXAM, false, 60);
        exam.setExamDatasetId(datasetId);
        exam.setExamDatasetVersion(1);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(exam));
        LocalDateTime assignmentDeadline = LocalDateTime.now().plusMinutes(10);
        stubAssignmentAndDeadline(assignmentDeadline);
        when(attemptRepository.findFirstByTaskRecordIdAndStatusOrderByAttemptNoDesc(
                recordId, TrainingAttempt.STATUS_IN_PROGRESS)).thenReturn(Optional.empty());
        when(attemptRepository.countByTaskRecordIdAndStatusIn(eq(recordId), any())).thenReturn(0L);
        when(attemptRepository.findMaxAttemptNo(recordId)).thenReturn(0);
        when(itemRepository.findByDatasetIdAndVersionAndStatus(any(), anyInt(), anyString()))
                .thenReturn(List.of(item("题一")));

        TrainingAttemptService.AttemptView view = service.startAttempt(recordId);

        assertTrue(view.attempt().getDeadline().isBefore(LocalDateTime.now().plusMinutes(11)),
                "考试截止应取 min(开始+时长, 分配截止)");
    }

    @Test
    void saveAnswers_whenLocked_conflicts() {
        when(accessService.requireLearnerAttempt(any())).thenReturn(TrainingAttempt.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId)
                .status(TrainingAttempt.STATUS_SUBMITTED).build());
        when(attemptRepository.saveAnswers(any(), anyString())).thenReturn(0);
        stubAssignmentAndDeadline(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.saveAnswers(UUID.randomUUID(), "{\"a\":\"b\"}"));

        assertEquals(409, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("不能修改答案"));
    }

    @Test
    void hints_exam_forbidden() {
        UUID attemptId = UUID.randomUUID();
        when(accessService.requireLearnerAttempt(attemptId)).thenReturn(TrainingAttempt.builder()
                .id(attemptId).taskId(taskId).type(TrainingAttempt.TYPE_EXAM)
                .status(TrainingAttempt.STATUS_IN_PROGRESS).build());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(
                task(TrainingTask.TYPE_EXAM, false, 60)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.hints(attemptId));

        assertEquals(403, ex.getErrorCode());
    }

    @Test
    void messages_practice_bindsConversationAndMarksTrainingMode() {
        UUID attemptId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID skillId = UUID.randomUUID();
        when(accessService.requireLearnerAttempt(attemptId)).thenReturn(TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskId(taskId)
                .type(TrainingAttempt.TYPE_PRACTICE)
                .status(TrainingAttempt.STATUS_IN_PROGRESS)
                .skillId(skillId).conversationId(conversationId).build());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(
                task(TrainingTask.TYPE_PRACTICE, true, null)));
        stubAssignmentAndDeadline(null);
        when(chatStreamService.respondPractice(any(), any(), any(), any())).thenReturn(Flux.empty());

        PracticeRespondRequest request = PracticeRespondRequest.builder()
                .message("客户说太贵了").roundNumber(1).totalAngles(3).build();
        service.messages(attemptId, request).blockLast();

        ArgumentCaptor<PracticeRespondRequest> captor =
                ArgumentCaptor.forClass(PracticeRespondRequest.class);
        verify(chatStreamService).respondPractice(eq(skillId), captor.capture(), eq(userId), any());
        assertEquals(conversationId.toString(), captor.getValue().getConversationId());
        assertTrue(Boolean.TRUE.equals(captor.getValue().getTrainingMode()),
                "培训陪练必须标记 trainingMode，避免污染分身互动统计");
    }

    @Test
    void practiceMessages_returnsOwnTranscript() {
        UUID attemptId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(accessService.requireLearnerAttempt(attemptId)).thenReturn(TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskId(taskId)
                .type(TrainingAttempt.TYPE_PRACTICE)
                .status(TrainingAttempt.STATUS_IN_PROGRESS)
                .conversationId(conversationId).build());
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(
                com.aiextract.model.SkillConversation.builder()
                        .id(conversationId).userId(userId).skillId(UUID.randomUUID()).build()));
        when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId))
                .thenReturn(List.of(
                        com.aiextract.model.SkillMessage.builder().id(UUID.randomUUID())
                                .conversationId(conversationId).role("user").roleLabel("我")
                                .content("您先说说需求").createdAt(LocalDateTime.now()).build(),
                        com.aiextract.model.SkillMessage.builder().id(UUID.randomUUID())
                                .conversationId(conversationId).role("assistant").roleLabel("客户")
                                .content("价格能不能再低点").createdAt(LocalDateTime.now()).build()));

        List<Map<String, Object>> rows = service.practiceMessages(attemptId);

        assertEquals(2, rows.size());
        assertEquals("user", rows.get(0).get("role"));
        assertEquals("价格能不能再低点", rows.get(1).get("content"));
        verify(accessService).requireLearnerAttempt(attemptId);
    }

    @Test
    void practiceMessages_examAttempt_rejected() {
        UUID attemptId = UUID.randomUUID();
        when(accessService.requireLearnerAttempt(attemptId)).thenReturn(TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskId(taskId)
                .type(TrainingAttempt.TYPE_EXAM)
                .status(TrainingAttempt.STATUS_IN_PROGRESS).build());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.practiceMessages(attemptId));

        assertEquals(400, ex.getErrorCode());
        verify(messageRepository, never()).findByConversationIdOrderByCreatedAtAsc(any());
    }

    @Test
    void practiceMessages_conversationBelongsToAnotherUser_notFound() {
        UUID attemptId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(accessService.requireLearnerAttempt(attemptId)).thenReturn(TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskId(taskId)
                .type(TrainingAttempt.TYPE_PRACTICE)
                .status(TrainingAttempt.STATUS_IN_PROGRESS)
                .conversationId(conversationId).build());
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(
                com.aiextract.model.SkillConversation.builder()
                        .id(conversationId).userId(UUID.randomUUID()).skillId(UUID.randomUUID()).build()));

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.practiceMessages(attemptId)).getErrorCode());
        verify(messageRepository, never()).findByConversationIdOrderByCreatedAtAsc(any());
    }

    @Test
    void practiceMessages_foreignAttempt_notFound() {
        UUID attemptId = UUID.randomUUID();
        when(accessService.requireLearnerAttempt(attemptId))
                .thenThrow(new BusinessException(404, "培训尝试不存在"));

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.practiceMessages(attemptId)).getErrorCode());
    }

    // ==================== T5：提交 / 成绩 ====================

    @Test
    void submit_inProgress_locksAnswersAndMarksSubmitted() {
        UUID attemptId = UUID.randomUUID();
        TrainingAttempt attempt = TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskId(taskId).taskRecordId(recordId)
                .type(TrainingAttempt.TYPE_PRACTICE).status(TrainingAttempt.STATUS_IN_PROGRESS)
                .attemptNo(1).build();
        when(accessService.requireLearnerAttempt(attemptId)).thenReturn(attempt);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(
                task(TrainingTask.TYPE_PRACTICE, true, null)));
        stubAssignmentAndDeadline(null);
        when(attemptRepository.submit(eq(attemptId), eq(TrainingAttempt.STATUS_IN_PROGRESS),
                eq(TrainingAttempt.STATUS_SUBMITTED), any())).thenReturn(1);

        TrainingAttemptService.AttemptView view = service.submit(attemptId);

        assertEquals(TrainingAttempt.STATUS_SUBMITTED, view.attempt().getStatus());
        // 提交后只剩「看成绩」；不得再下发答题/提交/发言/提示
        assertEquals(List.of("view_result"), view.allowedActions());
        verify(attemptRepository).submit(eq(attemptId), eq(TrainingAttempt.STATUS_IN_PROGRESS),
                eq(TrainingAttempt.STATUS_SUBMITTED), any());
    }

    @Test
    void submit_repeated_isIdempotent() {
        UUID attemptId = UUID.randomUUID();
        TrainingAttempt attempt = TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskId(taskId).taskRecordId(recordId)
                .type(TrainingAttempt.TYPE_EXAM).status(TrainingAttempt.STATUS_SUBMITTED)
                .attemptNo(1).build();
        when(accessService.requireLearnerAttempt(attemptId)).thenReturn(attempt);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(
                task(TrainingTask.TYPE_EXAM, false, 60)));

        TrainingAttemptService.AttemptView view = service.submit(attemptId);

        assertEquals(TrainingAttempt.STATUS_SUBMITTED, view.attempt().getStatus());
        verify(attemptRepository, never()).submit(any(), anyString(), anyString(), any());
    }

    @Test
    void submit_pastDeadlineWithTimeoutFailPolicy_conflicts() {
        UUID attemptId = UUID.randomUUID();
        TrainingTask practice = task(TrainingTask.TYPE_PRACTICE, true, null);
        practice.setDeadlinePolicy(TrainingTask.DEADLINE_TIMEOUT_FAIL);
        when(accessService.requireLearnerAttempt(attemptId)).thenReturn(TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskId(taskId).taskRecordId(recordId)
                .type(TrainingAttempt.TYPE_PRACTICE).status(TrainingAttempt.STATUS_IN_PROGRESS)
                .attemptNo(1).build());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(practice));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId).companyId(companyId)
                        .learnerId(userId).status(TrainingAssignment.STATUS_IN_PROGRESS)
                        .deadline(LocalDateTime.now().minusMinutes(1)).build()));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.submit(attemptId));

        assertEquals(409, ex.getErrorCode());
        verify(attemptRepository, never()).submit(any(), anyString(), anyString(), any());
    }

    @Test
    void submit_pastDeadlineWithAutoSubmitPolicy_allowed() {
        UUID attemptId = UUID.randomUUID();
        when(accessService.requireLearnerAttempt(attemptId)).thenReturn(TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskId(taskId).taskRecordId(recordId)
                .type(TrainingAttempt.TYPE_PRACTICE).status(TrainingAttempt.STATUS_IN_PROGRESS)
                .attemptNo(1).build());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(
                task(TrainingTask.TYPE_PRACTICE, true, null)));
        stubAssignmentAndDeadline(null);
        when(attemptRepository.submit(eq(attemptId), eq(TrainingAttempt.STATUS_IN_PROGRESS),
                eq(TrainingAttempt.STATUS_SUBMITTED), any())).thenReturn(1);

        TrainingAttemptService.AttemptView view = service.submit(attemptId);

        assertEquals(TrainingAttempt.STATUS_SUBMITTED, view.attempt().getStatus());
    }

    @Test
    void getResult_finalizedExam_stripsGoldPointDetails() throws Exception {
        UUID attemptId = UUID.randomUUID();
        String score = "{\"total_score\":80,\"verdict\":\"pass\",\"reason\":\"覆盖了要点A\","
                + "\"items\":[{\"itemId\":\"i1\",\"total_score\":80,\"verdict\":\"pass\","
                + "\"reason\":\"覆盖了要点A\",\"hit_points\":[\"要点A\"],"
                + "\"missed_points\":[],\"forbidden_hits\":[]}]}";
        when(accessService.requireLearnerAttempt(attemptId)).thenReturn(TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskId(taskId).taskRecordId(recordId)
                .type(TrainingAttempt.TYPE_EXAM).status(TrainingAttempt.STATUS_FINALIZED)
                .result(TrainingAttempt.RESULT_PASS).attemptNo(1).score(score).build());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(
                task(TrainingTask.TYPE_EXAM, false, 60)));
        when(reviewRepository.findByAttemptIdOrderByCreatedAtAsc(attemptId)).thenReturn(List.of());

        TrainingAttemptService.AttemptResultView view = service.getResult(attemptId);

        JsonNode sanitized = view.score();
        assertEquals(80, sanitized.path("total_score").asInt());
        assertFalse(sanitized.has("reason"), "考试结算不得下发模型理由（可能引用金标）");
        JsonNode item = sanitized.path("items").get(0);
        assertFalse(item.has("hit_points"));
        assertFalse(item.has("missed_points"));
        assertFalse(item.has("forbidden_hits"));
        assertFalse(item.has("reason"));
    }

    @Test
    void getResult_pendingReview_doesNotExposeScore() {
        UUID attemptId = UUID.randomUUID();
        when(accessService.requireLearnerAttempt(attemptId)).thenReturn(TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskId(taskId).taskRecordId(recordId)
                .type(TrainingAttempt.TYPE_EXAM).status(TrainingAttempt.STATUS_PENDING_REVIEW)
                .attemptNo(1).score("{\"total_score\":80}").build());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(
                task(TrainingAttempt.TYPE_EXAM, false, 60)));
        when(reviewRepository.findByAttemptIdOrderByCreatedAtAsc(attemptId)).thenReturn(List.of());

        TrainingAttemptService.AttemptResultView view = service.getResult(attemptId);

        assertNull(view.score(), "待复核期间不能提前下发成绩");
    }

    @Test
    void startAttempt_recordPendingReview_conflicts() {
        when(accessService.requireLearnerRecord(recordId))
                .thenReturn(record(TrainingTaskRecord.STATUS_PENDING_REVIEW));
        when(recordRepository.findByIdForUpdate(recordId))
                .thenReturn(Optional.of(record(TrainingTaskRecord.STATUS_PENDING_REVIEW)));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(
                task(TrainingTask.TYPE_PRACTICE, true, null)));
        stubAssignmentAndDeadline(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startAttempt(recordId));

        assertEquals(409, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("复核中"));
    }

    @Test
    void startAttempt_alreadyPassed_conflicts() {
        when(accessService.requireLearnerRecord(recordId))
                .thenReturn(record(TrainingTaskRecord.STATUS_PASSED));
        when(recordRepository.findByIdForUpdate(recordId))
                .thenReturn(Optional.of(record(TrainingTaskRecord.STATUS_PASSED)));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(
                task(TrainingTask.TYPE_PRACTICE, true, null)));
        stubAssignmentAndDeadline(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.startAttempt(recordId));

        assertEquals(409, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("已通过"));
        verify(attemptRepository, never()).save(any());
    }

    @Test
    void startAttempt_inFlight_returnsExistingAttemptInsteadOfCreatingNew() {
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(
                task(TrainingTask.TYPE_PRACTICE, true, null)));
        stubAssignmentAndDeadline(null);
        when(attemptRepository.findFirstByTaskRecordIdAndStatusOrderByAttemptNoDesc(
                recordId, TrainingAttempt.STATUS_IN_PROGRESS)).thenReturn(Optional.empty());
        TrainingAttempt submitted = TrainingAttempt.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId).taskId(taskId)
                .taskRecordId(recordId).type(TrainingAttempt.TYPE_PRACTICE)
                .status(TrainingAttempt.STATUS_PENDING_REVIEW).attemptNo(2).build();
        when(attemptRepository.findFirstByTaskRecordIdAndStatusInOrderByAttemptNoDesc(
                eq(recordId), any())).thenReturn(Optional.of(submitted));

        TrainingAttemptService.AttemptView view = service.startAttempt(recordId);

        assertEquals(submitted.getId(), view.attempt().getId());
        verify(attemptRepository, never()).saveAndFlush(any());
    }

    @Test
    void getResult_finalizedWithReview_overlaysFinalScoreAndVerdict() {
        UUID attemptId = UUID.randomUUID();
        when(accessService.requireLearnerAttempt(attemptId)).thenReturn(TrainingAttempt.builder()
                .id(attemptId).assignmentId(assignmentId).taskId(taskId).taskRecordId(recordId)
                .type(TrainingAttempt.TYPE_EXAM).status(TrainingAttempt.STATUS_FINALIZED)
                .result(TrainingAttempt.RESULT_PASS).attemptNo(1)
                .score("{\"total_score\":58,\"verdict\":\"fail\",\"need_review\":true}")
                .build());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(
                task(TrainingTask.TYPE_EXAM, false, 60)));
        when(reviewRepository.findByAttemptIdOrderByCreatedAtAsc(attemptId)).thenReturn(List.of(
                com.aiextract.model.TrainingReview.builder()
                        .id(UUID.randomUUID()).attemptId(attemptId)
                        .action("pass").reason("人工核对后通过")
                        .adjustedScore("{\"total_score\":62}").build()));

        TrainingAttemptService.AttemptResultView view = service.getResult(attemptId);

        JsonNode score = view.score();
        assertEquals(62, score.path("total_score").asInt(), "人工调整分应成为最终分");
        assertEquals(58, score.path("ai_total_score").asInt(), "AI 原分保留");
        assertEquals("pass", score.path("verdict").asText(), "最终结论与 attempt.result 一致");
        assertFalse(score.path("need_review").asBoolean());
        assertTrue(score.path("human_reviewed").asBoolean());
    }

    @Test
    void getRecordAttempts_returnsHistoryAndRemainingCount() {
        when(accessService.requireLearnerRecord(recordId)).thenReturn(record(TrainingTaskRecord.STATUS_FAILED));
        TrainingTask task = task(TrainingTask.TYPE_PRACTICE, true, null);
        task.setMaxAttempts(3);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        TrainingAttempt first = TrainingAttempt.builder()
                .id(UUID.randomUUID()).taskRecordId(recordId).attemptNo(1)
                .status(TrainingAttempt.STATUS_FINALIZED).result(TrainingAttempt.RESULT_FAIL).build();
        TrainingAttempt second = TrainingAttempt.builder()
                .id(UUID.randomUUID()).taskRecordId(recordId).attemptNo(2)
                .status(TrainingAttempt.STATUS_PENDING_REVIEW).build();
        when(attemptRepository.findByTaskRecordIdInOrderByAttemptNoDesc(List.of(recordId)))
                .thenReturn(List.of(second, first));
        when(attemptRepository.countByTaskRecordIdAndStatusIn(eq(recordId), any())).thenReturn(2L);
        when(reviewRepository.findByAttemptIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());

        TrainingAttemptService.AttemptHistory history = service.getRecordAttempts(recordId);

        assertEquals(2, history.attempts().size());
        assertEquals(2, history.usedAttempts());
        assertEquals(1, history.remainingAttempts());
    }

    // ==================== 辅助 ====================

    private void stubAttemptContext(String type, boolean allowHints, int maxAttempts, Integer duration) {
        TrainingTask task = task(type, allowHints, duration);
        task.setMaxAttempts(maxAttempts);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        stubAssignmentAndDeadline(null);
        when(attemptRepository.findFirstByTaskRecordIdAndStatusOrderByAttemptNoDesc(
                recordId, TrainingAttempt.STATUS_IN_PROGRESS)).thenReturn(Optional.empty());
    }

    private void stubAssignmentAndDeadline(LocalDateTime deadline) {
        TrainingAssignment assignment = TrainingAssignment.builder()
                .id(assignmentId).companyId(companyId).cohortId(UUID.randomUUID())
                .learnerId(userId).status(TrainingAssignment.STATUS_IN_PROGRESS)
                .deadline(deadline).build();
        // 开考走行锁（与延期串行化），提交/续接走普通读
        org.mockito.Mockito.lenient()
                .when(assignmentRepository.findByIdForUpdate(assignmentId))
                .thenReturn(Optional.of(assignment));
        org.mockito.Mockito.lenient()
                .when(assignmentRepository.findById(assignmentId))
                .thenReturn(Optional.of(assignment));
    }

    private TrainingTaskRecord record(String status) {
        return TrainingTaskRecord.builder()
                .id(recordId).assignmentId(assignmentId).taskId(taskId).status(status).build();
    }

    private TrainingTask task(String type, boolean allowHints, Integer duration) {
        return TrainingTask.builder()
                .id(taskId).planId(UUID.randomUUID()).version(1)
                .type(type).title("任务").description("说明")
                .phaseNo(1).orderNo(1).isRequired(true)
                .allowHints(allowHints).maxAttempts(2)
                .examDurationMinutes(duration)
                .resourceType(TrainingTask.RESOURCE_SKILL)
                .resourceId(UUID.randomUUID())
                .build();
    }

    private EvaluationItem item(String question) {
        return EvaluationItem.builder()
                .id(UUID.randomUUID()).question(question)
                .expectedPoints("[\"要点一\"]")
                .forbiddenPoints("[\"红线\"]")
                .build();
    }
}
