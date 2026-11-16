package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.PromptLoader;
import com.aiextract.config.RolePermissions;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.PracticeRespondRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ChatChunk;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.SkillConversation;
import com.aiextract.model.SkillMessage;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingReview;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.SkillConversationRepository;
import com.aiextract.repository.SkillMessageRepository;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingReviewRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 培训尝试服务 —— 陪练/考试的创建、恢复、答题与提示。
 *
 * <p><b>正确答案安全（D23）</b>：考试题面快照含金标（expectedPoints/forbiddenPoints/scoringRule），
 * 所有面向学员的响应都必须经过 {@link #stripAnswerKey}，只下发题面。
 * 开始/续接、恢复、成绩三条路径共用同一个视图构造方法。</p>
 *
 * <p><b>答题原子性（D24）</b>：草稿保存用条件 UPDATE（仅 in_progress 可写），
 * 提交后返回 0 行即拒绝，杜绝「提交后又把答案写回去」。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingAttemptService {

    /** 答题 JSON 上限（字符数），防止单请求把 jsonb 写爆 */
    private static final int MAX_ANSWERS_CHARS = 256 * 1024;

    /** 单次陪练最多发言条数（学员侧），防止一次尝试内无限触发 LLM */
    @org.springframework.beans.factory.annotation.Value("${app.training.practice.max-messages-per-attempt:40}")
    private int maxPracticeMessages;

    private final TrainingAccessService accessService;
    private final CompanyScopeService companyScopeService;
    private final TrainingTaskRecordRepository recordRepository;
    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingAttemptRepository attemptRepository;
    private final TrainingReviewRepository reviewRepository;
    private final TrainingTaskRepository taskRepository;
    private final EvaluationItemRepository itemRepository;
    private final SkillConversationRepository conversationRepository;
    private final SkillMessageRepository messageRepository;
    private final TrainingRateLimiter rateLimiter;
    private final TrainingProgressService progressService;
    private final ChatStreamService chatStreamService;
    private final ChatStreamAdapter chatStreamAdapter;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    /** 学员可见的尝试视图：题面（已 strip）+ 自己的答案 + 剩余时间 */
    public record AttemptView(TrainingAttempt attempt, TrainingTask task,
                              JsonNode questions, Long remainingSeconds,
                              List<String> allowedActions,
                              boolean deadlineExceeded, String terminationReason,
                              /** 微练场景（scenario/clientRole），非微练为 null */
                              JsonNode scenario) {
    }

    /**
     * 学员可见的成绩视图。
     *
     * <p>{@code score} 只在定稿后非空；{@code usedAttempts/remainingAttempts/canRetry}
     * 由服务端按「已定稿 + 待复核」次数计算，前端不得用 attemptNo 推导。</p>
     */
    public record AttemptResultView(TrainingAttempt attempt, TrainingTask task,
                                    List<TrainingReview> reviews, JsonNode score,
                                    JsonNode questions, JsonNode answers, JsonNode scenario,
                                    int usedAttempts, int remainingAttempts, boolean canRetry,
                                    String retryBlockedReason) {
    }

    /** 补训后旧轮次不允许继续开考/提交，否则计数会重复或触发 CHECK 失败（P1-7）。 */
    private void assertLatestRound(TrainingTaskRecord record) {
        int maxRound = recordRepository.findMaxRoundNo(record.getAssignmentId(), record.getTaskId());
        int round = record.getRoundNo() == null ? 1 : record.getRoundNo();
        if (round < maxRound) {
            throw BusinessException.conflict("TRAINING_OLD_ROUND",
                    "该任务已安排第 " + maxRound + " 轮补训，旧轮次不能再操作");
        }
    }

    // ==================== 创建 / 续接 ====================

    /**
     * 开始（或续接）一次尝试。
     *
     * <p>已有进行中的尝试直接返回，不新建；超过 max_attempts 返回 409 并说明原因。</p>
     */
    @Transactional
    public AttemptView startAttempt(UUID recordId) {
        accessService.requireLearnerRecord(recordId); // 归属校验（本人 + 本企业）
        // 行锁串行化同一进度行的并发开始：否则两个请求会算出同一个 attempt_no，
        // 唯一约束要到提交时才报错，异常落在 try/catch 之外，前端收到 500
        TrainingTaskRecord record = recordRepository.findByIdForUpdate(recordId)
                .orElseThrow(() -> new BusinessException(404, "培训任务不存在或不可访问"));
        assertLatestRound(record);
        TrainingTask task = taskRepository.findById(record.getTaskId())
                .orElseThrow(() -> new BusinessException(404, "培训任务不存在"));
        if (task.isReading()) {
            throw new BusinessException(400, "阅读任务无需开始，请直接标记已读");
        }
        // 微练绑定分身时必须仍已发布：否则开练后每轮都失败，还会白耗一次尝试
        if (task.isDrill() && TrainingTask.RESOURCE_SKILL.equals(task.getResourceType())) {
            if (task.getResourceId() == null) {
                throw new BusinessException(400, "微练任务未绑定分身");
            }
            companyScopeService.assertSkillUsable(task.getResourceId(), task.getTitle());
        }
        // 锁分配行：与「延期」串行化。否则开考时读到的旧截止会冻结进 attempt.deadline，
        // 并发延期提交后不会被 extendInProgressAttempts 补到，考试会被按旧时间代交
        TrainingAssignment assignment = assignmentRepository
                .findByIdForUpdate(record.getAssignmentId())
                .orElseThrow(() -> new BusinessException(404, "培训分配不存在"));
        if (!assignment.isOpen()) {
            throw new BusinessException(409, "TRAINING_ASSIGNMENT_CLOSED", "该培训已结束，不能再开始");
        }
        if (assignment.getDeadline() != null
                && assignment.getDeadline().isBefore(LocalDateTime.now())) {
            throw new BusinessException(403, "已超过截止时间，不能再开始");
        }
        if (TrainingTaskRecord.STATUS_LOCKED.equals(record.getStatus())) {
            throw new BusinessException(403, "任务尚未解锁，请先完成前置任务");
        }
        // 已通过/已豁免是终态：再开尝试会产生一份可以推翻既有结论的新证据，
        // 导致 counted 数据与解锁关系被回滚，直接拒绝
        if (TrainingTaskRecord.STATUS_PASSED.equals(record.getStatus())
                || TrainingTaskRecord.STATUS_WAIVED.equals(record.getStatus())) {
            throw new BusinessException(409, "TRAINING_TASK_ALREADY_PASSED", "该任务已通过，无需再次尝试");
        }

        Optional<TrainingAttempt> ongoing = attemptRepository
                .findFirstByTaskRecordIdAndStatusOrderByAttemptNoDesc(
                        recordId, TrainingAttempt.STATUS_IN_PROGRESS);
        if (ongoing.isPresent()) {
            return toView(ongoing.get(), task);
        }
        // 已提交/评分中/待复核：不新建，也不报错——直接返回该尝试让前端恢复。
        // 这三种状态都算「有结论未出」，必须阻断新尝试，否则同一任务出现并行证据。
        Optional<TrainingAttempt> inFlight = attemptRepository
                .findFirstByTaskRecordIdAndStatusInOrderByAttemptNoDesc(recordId,
                        List.of(TrainingAttempt.STATUS_SUBMITTED,
                                TrainingAttempt.STATUS_GRADING,
                                TrainingAttempt.STATUS_PENDING_REVIEW));
        if (inFlight.isPresent()) {
            return toView(inFlight.get(), task);
        }
        if (TrainingTaskRecord.STATUS_PENDING_REVIEW.equals(record.getStatus())) {
            // 数据异常：进度行在待复核但没有对应尝试，不能放行新尝试
            throw new BusinessException(409, "TRAINING_PENDING_REVIEW", "本次任务正在复核中，请等待结果");
        }
        // 只有「已出结论/待复核」的尝试占用次数；评分失败的 error 与进行中不算
        long used = attemptRepository.countByTaskRecordIdAndStatusIn(recordId,
                List.of(TrainingAttempt.STATUS_FINALIZED, TrainingAttempt.STATUS_PENDING_REVIEW));
        if (used >= task.getMaxAttempts()) {
            throw new BusinessException(409, "TRAINING_ATTEMPT_LIMIT",
                    "已达最大尝试次数（" + task.getMaxAttempts() + " 次），如需补训请联系主管");
        }

        LocalDateTime now = LocalDateTime.now();
        int attemptNo = attemptRepository.findMaxAttemptNo(recordId) + 1;
        TrainingAttempt.TrainingAttemptBuilder builder = TrainingAttempt.builder()
                .id(UUID.randomUUID())
                .assignmentId(record.getAssignmentId())
                .taskId(task.getId())
                .taskRecordId(recordId)
                .type(task.getType())
                .attemptNo(attemptNo)
                .status(TrainingAttempt.STATUS_IN_PROGRESS)
                .gradingAttempts(0)
                .startedAt(now)
                .createdAt(now)
                .updatedAt(now);

        if (task.isExam()) {
            builder.questionSnapshot(buildExamSnapshot(task))
                    .deadline(resolveExamDeadline(task, assignment, now));
        } else if (task.isDrill()) {
            // 微练不限时：截止跟随分配，题面来自创建时冻结的场景卡
            builder.questionSnapshot(buildDrillSnapshot(task))
                    .deadline(assignment.getDeadline());
        } else {
            if (task.getResourceId() == null) {
                throw new BusinessException(400, "陪练任务未绑定分身");
            }
            builder.skillId(task.getResourceId());
            UUID conversationId = createPracticeConversation(task, record.getAssignmentId());
            builder.conversationId(conversationId);
        }

        TrainingAttempt attempt;
        try {
            // 行锁下不应再冲突；saveAndFlush 让异常在当前事务内抛出，便于给出可读错误
            attempt = attemptRepository.saveAndFlush(builder.build());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 事务已被标记 rollback-only，不能回查后继续；直接让前端刷新重试
            throw new BusinessException(409, "TRAINING_ATTEMPT_CONFLICT", "并发开始尝试，请刷新后重试");
        }
        if (TrainingTaskRecord.STATUS_AVAILABLE.equals(record.getStatus())) {
            recordRepository.markStarted(recordId, TrainingTaskRecord.STATUS_AVAILABLE,
                    TrainingTaskRecord.STATUS_IN_PROGRESS, now);
        }
        progressService.markAssignmentStarted(record.getAssignmentId());
        return toView(attempt, task);
    }

    /** 恢复：刷新页面按 id 重取，不新建尝试 */
    public AttemptView getAttempt(UUID attemptId) {
        TrainingAttempt attempt = accessService.requireLearnerAttempt(attemptId);
        TrainingTask task = taskRepository.findById(attempt.getTaskId())
                .orElseThrow(() -> new BusinessException(404, "培训任务不存在"));
        return toView(attempt, task);
    }

    // ==================== 尝试历史 ====================

    /** 一次尝试 + 最新一条复核（学员历史列表用） */
    public record AttemptSummary(TrainingAttempt attempt, TrainingReview latestReview) {
    }

    /** 某任务下学员的全部尝试与次数口径 */
    public record AttemptHistory(TrainingTask task, List<AttemptSummary> attempts,
                                 int usedAttempts, int remainingAttempts) {
    }

    /**
     * 某任务记录的尝试历史。
     *
     * <p>没有这个入口，学员交卷后离开页面就只能靠 URL 里的 attemptId 找回成绩。</p>
     */
    public AttemptHistory getRecordAttempts(UUID recordId) {
        TrainingTaskRecord record = accessService.requireLearnerRecord(recordId);
        TrainingTask task = taskRepository.findById(record.getTaskId())
                .orElseThrow(() -> new BusinessException(404, "培训任务不存在"));
        List<TrainingAttempt> attempts = attemptRepository
                .findByTaskRecordIdInOrderByAttemptNoDesc(List.of(recordId));

        Map<UUID, TrainingReview> latestReviews = new HashMap<>();
        List<UUID> attemptIds = attempts.stream().map(TrainingAttempt::getId).toList();
        if (!attemptIds.isEmpty()) {
            // 按时间升序，后写入的覆盖前面的，最终留下最新一条
            for (TrainingReview review : reviewRepository
                    .findByAttemptIdInOrderByCreatedAtAsc(attemptIds)) {
                latestReviews.put(review.getAttemptId(), review);
            }
        }

        long used = attemptRepository.countByTaskRecordIdAndStatusIn(recordId,
                List.of(TrainingAttempt.STATUS_FINALIZED, TrainingAttempt.STATUS_PENDING_REVIEW));
        int remaining = (int) Math.max(0, task.getMaxAttempts() - used);
        List<AttemptSummary> rows = attempts.stream()
                .map(a -> new AttemptSummary(a, latestReviews.get(a.getId())))
                .toList();
        return new AttemptHistory(task, rows, (int) used, remaining);
    }

    // ==================== 提交 / 成绩 ====================

    /**
     * 交卷：in_progress → submitted，答案自此锁定。
     *
     * <p>幂等：已提交/评分中/待复核/已定稿都直接返回当前状态，不报错也不重复建任务——
     * 前端双击或断线重连都不应该产生第二份证据。只有评分失败的 error 不允许再提交，
     * 学员应重新开始一次尝试（error 不占 max_attempts）。</p>
     */
    @Transactional
    public AttemptView submit(UUID attemptId) {
        TrainingAttempt attempt = accessService.requireLearnerAttempt(attemptId);
        TrainingTask task = taskOf(attempt);
        if (TrainingAttempt.STATUS_ERROR.equals(attempt.getStatus())) {
            throw new BusinessException(409, "TRAINING_GRADING_FAILED", "本次尝试评分失败，请重新开始一次尝试");
        }
        if (!TrainingAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            return toView(attempt, task);
        }
        assertAssignmentOpen(attempt);
        if ((TrainingAttempt.TYPE_EXAM.equals(attempt.getType())
                || TrainingAttempt.TYPE_DRILL.equals(attempt.getType()))
                && (attempt.getQuestionSnapshot() == null || attempt.getQuestionSnapshot().isBlank())) {
            throw new BusinessException(409, "TRAINING_EXAM_SNAPSHOT_MISSING", "题面缺失，请联系管理员");
        }
        assertSubmitDeadline(attempt, task);

        LocalDateTime now = LocalDateTime.now();
        int updated = attemptRepository.submit(attemptId, TrainingAttempt.STATUS_IN_PROGRESS,
                TrainingAttempt.STATUS_SUBMITTED, now);
        if (updated == 0) {
            // 并发提交已成功：重读后按幂等路径返回，不能报 500
            TrainingAttempt latest = attemptRepository.findById(attemptId).orElse(attempt);
            return toView(latest, task);
        }
        // @Modifying 不刷新一级缓存，必须同步内存状态
        attempt.setStatus(TrainingAttempt.STATUS_SUBMITTED);
        attempt.setSubmittedAt(now);
        return toView(attempt, task);
    }

    /**
     * 成绩：评分中 / 待复核 / 已定稿 / 评分失败都返回真实状态。
     *
     * <p>只有 finalized 才下发评分明细，且考试明细经过 {@link #sanitizeScore} 剔除
     * hit/missed/forbidden（这些字段原样引用金标，等于泄题）。</p>
     */
    public AttemptResultView getResult(UUID attemptId) {
        TrainingAttempt attempt = accessService.requireLearnerAttempt(attemptId);
        TrainingTask task = taskOf(attempt);
        List<TrainingReview> reviews = reviewRepository.findByAttemptIdOrderByCreatedAtAsc(attemptId);
        TrainingReview latestReview = reviews.isEmpty() ? null : reviews.get(reviews.size() - 1);
        boolean finalized = TrainingAttempt.STATUS_FINALIZED.equals(attempt.getStatus());
        JsonNode score = finalized ? sanitizeScore(attempt, latestReview) : null;

        long used = attemptRepository.countByTaskRecordIdAndStatusIn(attempt.getTaskRecordId(),
                List.of(TrainingAttempt.STATUS_FINALIZED, TrainingAttempt.STATUS_PENDING_REVIEW));
        int remaining = (int) Math.max(0, task.getMaxAttempts() - used);
        String retryBlockedReason = retryBlockedReason(attempt, task, remaining);
        boolean canRetry = retryBlockedReason == null;

        JsonNode scenario = TrainingAttempt.TYPE_DRILL.equals(attempt.getType())
                ? scenarioOf(attempt.getQuestionSnapshot()) : null;
        return new AttemptResultView(attempt, task, reviews, score,
                stripAnswerKey(attempt.getQuestionSnapshot()),
                parseAnswersNode(attempt.getAnswers()), scenario, (int) used, remaining,
                canRetry, retryBlockedReason);
    }

    /**
     * 是否还能再考一次：任务未终结、分配可操作、未逾期、次数未用尽，
     * 且没有进行中/评分中/待复核的尝试（有的话应先处理那一次）。
     */
    private String retryBlockedReason(TrainingAttempt attempt, TrainingTask task, int remaining) {
        if (remaining <= 0) {
            return "attempt_limit";
        }
        TrainingTaskRecord record = recordRepository.findById(attempt.getTaskRecordId()).orElse(null);
        if (record == null) {
            return "task_state";
        }
        // failed 不是「不能再考」：补考正是从 failed 重新开始；只有 passed/waived 才是终态
        if (TrainingTaskRecord.STATUS_PASSED.equals(record.getStatus())
                || TrainingTaskRecord.STATUS_WAIVED.equals(record.getStatus())) {
            return "task_terminal";
        }
        // 评分失败会置 pending_review，此时必须等主管裁决，不能给「再试一次」按钮
        if (TrainingTaskRecord.STATUS_PENDING_REVIEW.equals(record.getStatus())) {
            return "pending_review";
        }
        TrainingAssignment assignment = assignmentRepository.findById(attempt.getAssignmentId())
                .orElse(null);
        if (assignment == null || !assignment.isOpen()) {
            return "assignment_closed";
        }
        if (assignment.getDeadline() != null
                && assignment.getDeadline().isBefore(LocalDateTime.now())) {
            return "deadline";
        }
        if (attemptRepository.existsByTaskRecordIdAndStatusIn(attempt.getTaskRecordId(),
                List.of(TrainingAttempt.STATUS_IN_PROGRESS, TrainingAttempt.STATUS_SUBMITTED,
                        TrainingAttempt.STATUS_GRADING, TrainingAttempt.STATUS_PENDING_REVIEW))) {
            return "attempt_in_flight";
        }
        return null;
    }

    /** 逾期提交：auto_submit 允许（草稿已在截止前冻结，等价于扫描器代交）；timeout_fail 拒绝 */
    private void assertSubmitDeadline(TrainingAttempt attempt, TrainingTask task) {
        if (TrainingTask.DEADLINE_AUTO_SUBMIT.equals(task.getDeadlinePolicy())) {
            return;
        }
        LocalDateTime deadline = attempt.getDeadline();
        if (deadline == null) {
            deadline = assignmentRepository.findById(attempt.getAssignmentId())
                    .map(TrainingAssignment::getDeadline)
                    .orElse(null);
        }
        if (deadline != null && deadline.isBefore(LocalDateTime.now())) {
            throw new BusinessException(409, "TRAINING_DEADLINE_PASSED", "已超过截止时间，本次尝试将按逾期策略判为未通过");
        }
    }

    /**
     * 成绩明细：剔除金标，并把人工复核结论合并成唯一口径。
     *
     * <p>复核后 {@code attempt.result} 才是最终结论；AI 的 verdict/need_review 只作为
     * 过程信息保留，不能让学员同时看到「最终 pass / AI fail」两套结论。</p>
     */
    private JsonNode sanitizeScore(TrainingAttempt attempt, TrainingReview latestReview) {
        String raw = attempt.getScore();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (!root.isObject()) {
                return null;
            }
            if (root instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                object.put("final_result", attempt.getResult());
                if (latestReview != null) {
                    object.put("human_reviewed", true);
                    object.put("need_review", false);
                    object.put("verdict", attempt.getResult());
                    int aiTotal = object.path("total_score").asInt(0);
                    object.put("ai_total_score", aiTotal);
                    JsonNode adjusted = readAdjustedTotal(latestReview.getAdjustedScore());
                    if (adjusted != null) {
                        object.put("total_score", adjusted.asInt());
                    }
                }
            }
            // 考试与微练都使用固定题面：金标要点不能在成绩里回显，否则重做时照抄即可绕过评分
            if (TrainingAttempt.TYPE_EXAM.equals(attempt.getType())
                    || TrainingAttempt.TYPE_DRILL.equals(attempt.getType())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) root).remove("reason");
                JsonNode items = root.get("items");
                if (items != null && items.isArray()) {
                    for (JsonNode item : items) {
                        if (item instanceof com.fasterxml.jackson.databind.node.ObjectNode row) {
                            row.remove("reason");
                            row.remove("hit_points");
                            row.remove("missed_points");
                            row.remove("forbidden_hits");
                        }
                    }
                }
            }
            return root;
        } catch (Exception e) {
            log.warn("评分明细解析失败 attemptId={}", attempt.getId(), e);
            return null;
        }
    }

    /** 复核后分数里认可的最终总分（没有或非法时返回 null，保留 AI 分） */
    private JsonNode readAdjustedTotal(String adjustedJson) {
        if (adjustedJson == null || adjustedJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(adjustedJson);
            JsonNode total = node.get("total_score");
            if (total == null || !total.isNumber()) {
                total = node.get("score");
            }
            return total != null && total.isNumber() ? total : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 学员自己的答案 JSON → 对象；空值/损坏时返回空对象，不阻断成绩页 */
    private JsonNode parseAnswersNode(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isObject() ? node : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private TrainingTask taskOf(TrainingAttempt attempt) {
        return taskRepository.findById(attempt.getTaskId())
                .orElseThrow(() -> new BusinessException(404, "培训任务不存在"));
    }

    // ==================== 答题 ====================

    /** 保存草稿：只有 in_progress 可写；已提交/已结束返回 409 */
    @Transactional
    public void saveAnswers(UUID attemptId, String answersJson) {
        TrainingAttempt attempt = accessService.requireLearnerAttempt(attemptId);
        assertAssignmentOpen(attempt);
        assertNotPastDeadline(attempt);
        validateAnswersJson(answersJson);
        int updated = attemptRepository.saveAnswers(attemptId, answersJson);
        if (updated == 0) {
            throw new BusinessException(409, "TRAINING_ATTEMPT_CLOSED", "本次尝试已提交或已结束，不能修改答案");
        }
    }

    // ==================== 陪练消息 / 提示 ====================

    /**
     * 陪练消息（SSE）。
     *
     * <p>复用分身对练链路，但强制绑定本次尝试的会话，并标记 trainingMode
     * （写 conversation_stats 时带 is_test=true，不污染分身互动统计）。</p>
     */
    public Flux<ChatChunk> messages(UUID attemptId, PracticeRespondRequest request) {
        TrainingAttempt attempt = accessService.requireLearnerAttempt(attemptId);
        if (!TrainingAttempt.TYPE_PRACTICE.equals(attempt.getType())) {
            throw new BusinessException(400, "只有陪练尝试可以发送消息");
        }
        if (!TrainingAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new BusinessException(409, "TRAINING_ATTEMPT_CLOSED", "本次尝试已结束，不能再发言");
        }
        assertAssignmentOpen(attempt);
        assertNotPastDeadline(attempt);
        TrainingTask task = taskRepository.findById(attempt.getTaskId())
                .orElseThrow(() -> new BusinessException(404, "培训任务不存在"));
        if (attempt.getSkillId() == null || attempt.getConversationId() == null) {
            throw new BusinessException(409, "TRAINING_PRACTICE_NOT_READY", "本次陪练会话未初始化，请重新开始任务");
        }
        long sent = messageRepository.countByConversationIdAndRole(
                attempt.getConversationId(), "user");
        // Redis 原子扣减防并发超发；Redis 异常时回退 DB 计数（可用性优先）
        if (!rateLimiter.tryConsumePracticeMessage(
                attempt.getId(), sent, maxPracticeMessages)) {
            throw new BusinessException(429,
                    "本次陪练发言已达上限（" + maxPracticeMessages + " 条），请提交或重新开始");
        }

        request.setConversationId(attempt.getConversationId().toString());
        request.setPracticeId(attempt.getId().toString());
        request.setTrainingMode(true);
        if (request.getSceneContext() == null || request.getSceneContext().isBlank()) {
            request.setSceneContext(nvl(task.getDescription(), task.getTitle()));
        }
        if (request.getSceneTag() == null || request.getSceneTag().isBlank()) {
            request.setSceneTag(nvl(task.getSceneTag(), ""));
        }
        return chatStreamService.respondPractice(
                attempt.getSkillId(), request, TokenContext.get(), currentRole());
    }

    /** 提示：考试任务或 allowHints=false 一律 403（服务端拒绝，不靠前端隐藏） */
    public Flux<ChatChunk> hints(UUID attemptId) {
        TrainingAttempt attempt = accessService.requireLearnerAttempt(attemptId);
        TrainingTask task = taskRepository.findById(attempt.getTaskId())
                .orElseThrow(() -> new BusinessException(404, "培训任务不存在"));
        // 只有显式允许才下发提示：与 toView 的 action 口径一致（null 视为不允许）
        if (task.isExam() || !Boolean.TRUE.equals(task.getAllowHints())) {
            throw new BusinessException(403, "考试任务不提供提示");
        }
        if (!TrainingAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            throw new BusinessException(409, "TRAINING_ATTEMPT_CLOSED", "本次尝试已结束，不能再请求提示");
        }
        assertAssignmentOpen(attempt);
        assertNotPastDeadline(attempt);
        // 提示每次都会触发 LLM 调用：额度由服务端控制，不靠前端自觉
        if (!rateLimiter.allowHint(attemptId)) {
            throw new BusinessException(429, "本次尝试的提示次数已用完");
        }

        // 补训轮次必须如实告诉模型，否则第 2 轮提示仍按首轮语境生成
        String roundNo = recordRepository.findById(attempt.getTaskRecordId())
                .map(r -> r.getRoundNo() == null ? "1" : String.valueOf(r.getRoundNo()))
                .orElse("1");
        // 模板名必须带 .md 后缀（PromptLoader 按文件名解析，其它调用点同样如此）
        String systemPrompt = promptLoader.format("training_hint.md", Map.of(
                "task_title", nvl(task.getTitle(), ""),
                "task_description", nvl(task.getDescription(), ""),
                "scene_tag", nvl(task.getSceneTag(), ""),
                "round_no", roundNo));
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", "请给我一个提示"));
        return chatStreamAdapter.chatStream(messages, Map.of("mode", "training_hint"))
                .map(ChatChunk::fromEventMap);
    }

    /**
     * 学员端陪练对话历史（刷新/续接恢复现场）。
     *
     * <p>只返回角色与正文，不涉及任何金标；归属由 {@code requireLearnerAttempt}
     * 保证（非本人 404），前端不能借 attemptId 读别人的对练记录。</p>
     */
    public List<Map<String, Object>> practiceMessages(UUID attemptId) {
        TrainingAttempt attempt = accessService.requireLearnerAttempt(attemptId);
        if (!TrainingAttempt.TYPE_PRACTICE.equals(attempt.getType())) {
            throw new BusinessException(400, "只有陪练尝试有对话记录");
        }
        if (attempt.getConversationId() == null) {
            return List.of();
        }
        // 纵深防御：会话属主必须是本人（会话由尝试创建时绑定，正常不会不一致）
        SkillConversation conversation = conversationRepository.findById(attempt.getConversationId())
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        if (!java.util.Objects.equals(conversation.getUserId(), TokenContext.get())) {
            throw new BusinessException(404, "培训数据不存在或不可访问");
        }
        List<SkillMessage> messages =
                messageRepository.findByConversationIdOrderByCreatedAtAsc(attempt.getConversationId());
        List<Map<String, Object>> rows = new ArrayList<>(messages.size());
        for (SkillMessage message : messages) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", message.getId().toString());
            row.put("role", message.getRole());
            row.put("roleLabel", message.getRoleLabel());
            row.put("content", message.getContent());
            row.put("createdAt", message.getCreatedAt());
            rows.add(row);
        }
        return rows;
    }

    // ==================== 内部 ====================

    /**
     * 学员视图：题面只下发 items，answerKey 永远不出服务端。
     *
     * <p>这是 D23 的唯一入口——开始、续接、恢复、成绩都必须走这里。</p>
     */
    private AttemptView toView(TrainingAttempt attempt, TrainingTask task) {
        JsonNode questions = stripAnswerKey(attempt.getQuestionSnapshot());
        LocalDateTime effectiveDeadline = effectiveDeadline(attempt);
        Long remaining = null;
        if (effectiveDeadline != null) {
            long seconds = Duration.between(LocalDateTime.now(), effectiveDeadline).getSeconds();
            remaining = Math.max(0, seconds);
        }
        boolean pastDeadline = effectiveDeadline != null
                && effectiveDeadline.isBefore(LocalDateTime.now());
        List<String> actions = new ArrayList<>();
        if (TrainingAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            boolean autoSubmit = TrainingTask.DEADLINE_AUTO_SUBMIT.equals(task.getDeadlinePolicy());
            if (!pastDeadline) {
                actions.add("save_answers");
                if (TrainingAttempt.TYPE_PRACTICE.equals(attempt.getType())) {
                    actions.add("send_message");
                }
                // 考试一律不给提示；提示接口本身也会 403，这里保持一致，避免下发死按钮
                if (!task.isExam() && Boolean.TRUE.equals(task.getAllowHints())) {
                    actions.add("hint");
                }
            }
            // 逾期的 auto_submit 仍允许交卷（草稿已在截止前冻结）；timeout_fail 不再下发交卷
            if (!pastDeadline || autoSubmit) {
                actions.add("submit");
            }
        } else if (TrainingAttempt.isValidStatus(attempt.getStatus())) {
            // 已提交/评分中/待复核/已定稿/失败：唯一可做的是看状态与成绩
            actions.add("view_result");
        }
        JsonNode scenario = null;
        if (TrainingAttempt.TYPE_DRILL.equals(attempt.getType())) {
            scenario = scenarioOf(attempt.getQuestionSnapshot());
        }
        return new AttemptView(attempt, task, questions, remaining, actions, pastDeadline,
                attempt.getTerminationReason(), scenario);
    }

    /** 微练场景头（不含 answerKey）；题面损坏时返回 null，由前端提示重新联系管理员 */
    private JsonNode scenarioOf(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
            JsonNode root = objectMapper.readTree(snapshot);
            node.put("scenario", root.path("scenario").asText(""));
            node.put("clientRole", root.path("clientRole").asText(""));
            return node;
        } catch (Exception e) {
            log.warn("微练场景解析失败", e);
            return null;
        }
    }

    /** 有效截止：考试取 attempt.deadline，陪练回落到分配截止 */
    private LocalDateTime effectiveDeadline(TrainingAttempt attempt) {
        if (attempt.getDeadline() != null) {
            return attempt.getDeadline();
        }
        return assignmentRepository.findById(attempt.getAssignmentId())
                .map(TrainingAssignment::getDeadline)
                .orElse(null);
    }

    /** 去掉 answerKey，只留题面；快照损坏时返回空数组而不是把原文透出 */
    private JsonNode stripAnswerKey(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode root = objectMapper.readTree(snapshot);
            JsonNode items = root.get("items");
            return items != null ? items : objectMapper.createArrayNode();
        } catch (Exception e) {
            log.warn("培训题面快照解析失败，按空题面返回", e);
            return objectMapper.createArrayNode();
        }
    }

    /** 冻结抽题与顺序：抽到的题面 + 金标（金标只存库，不下发） */
    private String buildExamSnapshot(TrainingTask task) {
        if (task.getExamDatasetId() == null || task.getExamDatasetVersion() == null) {
            throw new BusinessException(400, "考试任务未绑定题集");
        }
        List<EvaluationItem> items = itemRepository.findByDatasetIdAndVersionAndStatus(
                task.getExamDatasetId(), task.getExamDatasetVersion(), EvaluationItem.STATUS_ACTIVE);
        if (items.isEmpty()) {
            throw new BusinessException(400, "考试题集没有可用题目");
        }
        int count = task.getExamItemCount() == null ? items.size()
                : Math.min(task.getExamItemCount(), items.size());
        if (count > TrainingTask.MAX_EXAM_ITEMS) {
            // 历史数据可能超过上限：开考时就拒绝，避免评分阶段拖死 worker
            throw new BusinessException(400, "考试题目超过单场上限（"
                    + TrainingTask.MAX_EXAM_ITEMS + " 题），请联系管理员调整抽题数");
        }
        List<EvaluationItem> shuffled = new ArrayList<>(items);
        Collections.shuffle(shuffled, new Random());
        List<EvaluationItem> drawn = shuffled.subList(0, count);

        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode questionNodes = root.putArray("items");
        ObjectNode answerKey = root.putObject("answerKey");
        int order = 1;
        for (EvaluationItem item : drawn) {
            ObjectNode q = questionNodes.addObject();
            q.put("itemId", item.getId().toString());
            q.put("orderNo", order++);
            q.put("question", nvl(item.getQuestion(), ""));
            q.put("context", item.getContext());
            // 引用要求存的是 JSONB 文本，解析后再下发，前端不用二次 parse
            q.set("citationRequirement", jsonOrText(item.getCitationRequirement()));
            q.put("sceneTag", item.getSceneTag());

            ObjectNode key = answerKey.putObject(item.getId().toString());
            key.set("expectedPoints", readJsonOrNull(item.getExpectedPoints()));
            key.set("forbiddenPoints", readJsonOrNull(item.getForbiddenPoints()));
            key.put("scoringRule", item.getScoringRule());
            key.put("goldBasis", item.getGoldBasis());
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new BusinessException(500, "考试题面生成失败");
        }
    }

    /**
     * 微练题面：把任务上的场景卡展开成与考试同构的 items + answerKey，
     * 这样评分复用同一套确定性判定（要点覆盖率/红线/置信度），不另造一套口径。
     */
    private String buildDrillSnapshot(TrainingTask task) {
        String raw = task.getResourceSnapshot();
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(409, "微练内容缺失，请联系管理员重新生成后下发");
        }
        JsonNode card;
        try {
            card = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new BusinessException(409, "微练内容损坏，请联系管理员重新生成后下发");
        }
        JsonNode questions = card.path("questions");
        if (!questions.isArray() || questions.isEmpty()) {
            throw new BusinessException(409, "微练内容没有题目，请联系管理员重新生成后下发");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("scenario", card.path("scenario").asText(""));
        root.put("clientRole", card.path("clientRole").asText(""));
        ArrayNode questionNodes = root.putArray("items");
        ObjectNode answerKey = root.putObject("answerKey");
        int order = 1;
        for (JsonNode q : questions) {
            String itemId = "q" + order;
            ObjectNode item = questionNodes.addObject();
            item.put("itemId", itemId);
            item.put("orderNo", order);
            item.put("question", q.path("question").asText(""));
            item.put("context", q.path("context").asText(""));
            item.put("sceneTag", task.getSceneTag());
            ObjectNode key = answerKey.putObject(itemId);
            key.set("expectedPoints", q.path("expectedPoints"));
            key.put("scoringRule", q.path("scoringRule").asText(""));
            order++;
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new BusinessException(500, "微练题面生成失败");
        }
    }

    /** 考试截止 = min(开始 + 时长, 分配截止) */
    private LocalDateTime resolveExamDeadline(TrainingTask task, TrainingAssignment assignment,
                                              LocalDateTime now) {
        Integer minutes = task.getExamDurationMinutes();
        if (minutes == null || minutes <= 0) {
            throw new BusinessException(400, "考试任务未设置时长");
        }
        LocalDateTime examDeadline = now.plusMinutes(minutes);
        LocalDateTime assignmentDeadline = assignment.getDeadline();
        if (assignmentDeadline != null && assignmentDeadline.isBefore(examDeadline)) {
            return assignmentDeadline;
        }
        return examDeadline;
    }

    /** 陪练会话复用 skill_conversation；会话归属学员本人，断线可按 id 续接 */
    private UUID createPracticeConversation(TrainingTask task, UUID assignmentId) {
        LocalDateTime now = LocalDateTime.now();
        SkillConversation conversation = SkillConversation.builder()
                .id(UUID.randomUUID())
                .skillId(task.getResourceId())
                .userId(TokenContext.get())
                .title("培训陪练：" + nvl(task.getTitle(), ""))
                .mode("practice")
                .createdAt(now)
                .updatedAt(now)
                .build();
        conversationRepository.save(conversation);
        log.info("培训陪练会话已创建 assignmentId={}, conversationId={}, skillId={}",
                assignmentId, conversation.getId(), task.getResourceId());
        return conversation.getId();
    }

    /** 分配已取消/已认证后，任何写操作都必须拒绝（2026-10-29 T6 复审补） */
    private void assertAssignmentOpen(TrainingAttempt attempt) {
        TrainingAssignment assignment = assignmentRepository.findById(attempt.getAssignmentId())
                .orElse(null);
        if (assignment == null || !assignment.isOpen()) {
            throw new BusinessException(409, "TRAINING_ASSIGNMENT_CLOSED", "该培训已结束，不能再操作");
        }
    }

    /**
     * 截止后不允许再写：考试用 attempt.deadline（= min(开始+时长, 分配截止)），
     * 陪练没有 attempt.deadline，回落到分配截止。
     */
    private void assertNotPastDeadline(TrainingAttempt attempt) {
        LocalDateTime deadline = attempt.getDeadline();
        if (deadline == null) {
            deadline = assignmentRepository.findById(attempt.getAssignmentId())
                    .map(TrainingAssignment::getDeadline)
                    .orElse(null);
        }
        if (deadline != null && deadline.isBefore(LocalDateTime.now())) {
            throw new BusinessException(403, "已超过截止时间，不能再修改或继续");
        }
    }

    /** JSON 文本能解析就用节点，不能解析就按纯文本下发 */
    private JsonNode jsonOrText(String raw) {
        if (raw == null || raw.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(raw);
        }
    }

    private void validateAnswersJson(String answersJson) {
        if (answersJson == null || answersJson.isBlank()) {
            throw new BusinessException(400, "答案不能为空");
        }
        if (answersJson.length() > MAX_ANSWERS_CHARS) {
            throw new BusinessException(400, "答案过大（上限 256KB），请精简后重试");
        }
        try {
            JsonNode node = objectMapper.readTree(answersJson);
            if (!node.isObject()) {
                throw new BusinessException(400, "答案必须是 JSON 对象（题目 ID → 文本）");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "答案不是合法 JSON");
        }
    }

    private JsonNode readJsonOrNull(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.nullNode();
        }
    }

    /** 当前登录角色（respondPractice 仅用于透传，取不到时按 employee 处理） */
    private String currentRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return RolePermissions.EMPLOYEE;
        }
        Set<String> roles = Set.of(RolePermissions.SUPER_ADMIN, RolePermissions.COMPANY_ADMIN,
                RolePermissions.EMPLOYEE, RolePermissions.C_USER, RolePermissions.C_GUEST);
        return authentication.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(roles::contains)
                .findFirst()
                .orElse(RolePermissions.EMPLOYEE);
    }

    private String nvl(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
