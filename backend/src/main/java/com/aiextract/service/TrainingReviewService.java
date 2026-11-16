package com.aiextract.service;

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
import com.aiextract.repository.SkillMessageRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 复核服务 —— 管理侧查看尝试证据、给出最终结论。
 *
 * <p><b>两条硬约束</b>：</p>
 * <ol>
 *   <li>归属链冻结：attempt → task_record → assignment → cohort，统一走
 *       {@link TrainingAccessService#assertCanReview(UUID)}，禁止裸 {@code findById} 放行；</li>
 *   <li>不能自己复核自己（D26）：复核人等于学员时 403，super_admin 例外。</li>
 * </ol>
 *
 * <p>只有 {@code pending_review} 可以复核：自动定稿的尝试已经产生业务结论，
 * 没有「事后改分」的状态路径，避免历史被覆盖。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingReviewService {

    /** 人工纠正分数的上限（字符数），与答题 JSON 同口径防止写爆 jsonb */
    private static final int MAX_ADJUSTED_SCORE_CHARS = 32 * 1024;

    /** 退回理由最小长度：必须能构成可执行的整改要求 */
    private static final int MIN_REJECT_REASON_CHARS = 5;

    private final TrainingAccessService accessService;
    private final TrainingAttemptRepository attemptRepository;
    private final TrainingTaskRecordRepository recordRepository;
    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingTaskRepository taskRepository;
    private final TrainingReviewRepository reviewRepository;
    private final com.aiextract.repository.TrainingCertificationRepository certificationRepository;
    private final SkillMessageRepository messageRepository;
    private final UserRepository userRepository;
    private final TrainingProgressService progressService;
    private final CompanyScopeService companyScopeService;
    private final TrainingNotificationService notificationService;
    private final ObjectMapper objectMapper;

    /**
     * 管理侧尝试详情。
     *
     * <p>{@code exposeAnswerKey} 只在待复核/已定稿/评分失败时为 true：考试进行中
     * 连管理员也不下发金标，避免「自己考自己」或提前泄题。</p>
     */
    public record AdminAttemptDetail(TrainingAttempt attempt, TrainingTask task,
                                     TrainingAssignment assignment, TrainingCohort cohort,
                                     User learner, List<TrainingReview> reviews,
                                     List<com.aiextract.model.SkillMessage> messages,
                                     Map<UUID, String> reviewerNames, boolean exposeAnswerKey) {
    }

    /** 复核结果：定稿后的尝试 + 本次追加的复核记录 + 进度行最终状态（免二次刷新） */
    public record ReviewResult(TrainingAttempt attempt, TrainingReview review, String recordStatus) {
    }

    /** 单轮：记录 + 该轮尝试；effective=是否为当前有效轮 */
    public record TaskRound(TrainingTaskRecord record, List<TrainingAttempt> attempts, boolean effective) {
    }

    /** 学员某任务下的尝试历史（复核抽屉用）；顶层 record/attempts 为有效轮，rounds 含全部轮次 */
    public record TaskAttempts(TrainingTaskRecord record, TrainingTask task,
                               List<TrainingAttempt> attempts, List<TaskRound> rounds) {
    }

    /** 认证历史行：记录 + 批量解析出的确认/撤销人姓名（历史可追溯，不能只给 UUID） */
    public record CertificationHistoryRow(com.aiextract.model.TrainingCertification certification,
                                          String confirmedByName, String revokedByName) {
    }

    /** 学员的完整尝试清单：批次详情 → 学员抽屉的数据来源 */
    public record AssignmentAttempts(TrainingAssignment assignment, List<TaskAttempts> items,
                                     List<CertificationHistoryRow> certificationHistory) {
    }

    public AdminAttemptDetail getAttemptDetail(UUID attemptId) {
        TrainingAttempt attempt = requireAttempt(attemptId);
        TrainingTaskRecord record = requireRecord(attempt.getTaskRecordId());
        TrainingAssignment assignment = requireAssignment(record.getAssignmentId());
        TrainingCohort cohort = accessService.assertCanReview(assignment.getCohortId());
        if (!companyScopeService.isUnrestrictedDataScope()
                && Objects.equals(TokenContext.get(), assignment.getLearnerId())) {
            // 学员本人（哪怕同时是管理员/导师）不能通过管理接口看自己考试的金标
            throw new BusinessException(404, "培训数据不存在或不可访问");
        }
        TrainingTask task = taskRepository.findById(attempt.getTaskId())
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        User learner = userRepository.findById(assignment.getLearnerId()).orElse(null);
        List<TrainingReview> reviews = reviewRepository.findByAttemptIdOrderByCreatedAtAsc(attemptId);

        List<com.aiextract.model.SkillMessage> messages =
                TrainingAttempt.TYPE_PRACTICE.equals(attempt.getType())
                        && attempt.getConversationId() != null
                        ? messageRepository.findByConversationIdOrderByCreatedAtAsc(
                                attempt.getConversationId())
                        : List.of();
        Map<UUID, String> reviewerNames = new HashMap<>();
        List<UUID> reviewerIds = reviews.stream()
                .map(TrainingReview::getReviewerId).filter(Objects::nonNull).distinct().toList();
        if (!reviewerIds.isEmpty()) {
            for (User reviewer : userRepository.findAllById(reviewerIds)) {
                reviewerNames.put(reviewer.getId(), reviewer.getName());
            }
        }
        boolean exposeAnswerKey = TrainingAttempt.STATUS_PENDING_REVIEW.equals(attempt.getStatus())
                || TrainingAttempt.STATUS_FINALIZED.equals(attempt.getStatus())
                || TrainingAttempt.STATUS_ERROR.equals(attempt.getStatus());
        return new AdminAttemptDetail(attempt, task, assignment, cohort, learner, reviews,
                messages, reviewerNames, exposeAnswerKey);
    }

    /**
     * 学员的任务与尝试清单 —— 没有这个入口，复核人只知道 attemptId 而无法发现它。
     *
     * <p>记录、尝试、任务各查一次后在内存分组，不做逐行查询（无 N+1）。</p>
     */
    public AssignmentAttempts listAssignmentAttempts(UUID assignmentId) {
        TrainingAssignment assignment = requireAssignment(assignmentId);
        TrainingCohort cohort = accessService.assertCanReview(assignment.getCohortId());
        List<TrainingTaskRecord> records =
                recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId);
        // 按 task_record_id 查（命中 idx_tat_record）；training_attempt 上没有 assignment_id 索引
        List<UUID> recordIds = records.stream().map(TrainingTaskRecord::getId).toList();
        List<TrainingAttempt> attempts = recordIds.isEmpty() ? List.of()
                : attemptRepository.findByTaskRecordIdInOrderByAttemptNoDesc(recordIds);

        java.util.Map<UUID, List<TrainingAttempt>> byRecord = new java.util.HashMap<>();
        for (TrainingAttempt attempt : attempts) {
            byRecord.computeIfAbsent(attempt.getTaskRecordId(), k -> new java.util.ArrayList<>())
                    .add(attempt);
        }
        java.util.Map<UUID, TrainingTask> tasks = new java.util.HashMap<>();
        for (TrainingTask task : taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(
                        cohort.getPlanId(), cohort.getPlanVersion())) {
            tasks.put(task.getId(), task);
        }

        // 按任务聚合轮次：rounds 升序，最后一轮为有效轮
        java.util.Map<UUID, List<TrainingTaskRecord>> byTask = new java.util.HashMap<>();
        for (TrainingTaskRecord record : records) {
            byTask.computeIfAbsent(record.getTaskId(), k -> new java.util.ArrayList<>()).add(record);
        }
        List<TaskAttempts> items = new java.util.ArrayList<>(byTask.size());
        for (List<TrainingTaskRecord> roundRecords : byTask.values()) {
            roundRecords.sort(java.util.Comparator.comparingInt(
                    r -> r.getRoundNo() == null ? 1 : r.getRoundNo()));
            List<TaskRound> rounds = new java.util.ArrayList<>(roundRecords.size());
            TrainingTaskRecord effective = roundRecords.get(roundRecords.size() - 1);
            for (TrainingTaskRecord roundRecord : roundRecords) {
                List<TrainingAttempt> rows = new java.util.ArrayList<>(
                        byRecord.getOrDefault(roundRecord.getId(), List.of()));
                rows.sort(java.util.Comparator.comparing(TrainingAttempt::getAttemptNo).reversed());
                rounds.add(new TaskRound(roundRecord, rows, roundRecord.getId().equals(effective.getId())));
            }
            List<TrainingAttempt> effectiveAttempts = rounds.get(rounds.size() - 1).attempts();
            items.add(new TaskAttempts(effective, tasks.get(effective.getTaskId()),
                    effectiveAttempts, rounds));
        }
        items.sort(java.util.Comparator
                .comparingInt((TaskAttempts t) -> t.task() == null ? Integer.MAX_VALUE : t.task().getPhaseNo())
                .thenComparingInt(t -> t.task() == null ? Integer.MAX_VALUE : t.task().getOrderNo()));
        List<com.aiextract.model.TrainingCertification> certHistory =
                certificationRepository.findByAssignmentIdOrderByCreatedAtDesc(assignmentId);
        // 历史行要能直接看出「谁确认/谁撤销」：两次找出全部操作人后一次批量取姓名
        List<UUID> operatorIds = new java.util.ArrayList<>();
        for (com.aiextract.model.TrainingCertification c : certHistory) {
            if (c.getConfirmedBy() != null) {
                operatorIds.add(c.getConfirmedBy());
            }
            if (c.getRevokedBy() != null) {
                operatorIds.add(c.getRevokedBy());
            }
        }
        Map<UUID, String> operatorNames = new HashMap<>();
        if (!operatorIds.isEmpty()) {
            for (User operator : userRepository.findAllById(operatorIds.stream().distinct().toList())) {
                operatorNames.put(operator.getId(), operator.getName());
            }
        }
        List<CertificationHistoryRow> certRows = certHistory.stream()
                .map(c -> new CertificationHistoryRow(c,
                        operatorNames.get(c.getConfirmedBy()),
                        operatorNames.get(c.getRevokedBy())))
                .toList();
        return new AssignmentAttempts(assignment, items, certRows);
    }

    /**
     * 复核：pending_review → finalized，并把结论回写任务进度。
     *
     * <p>条件迁移失败即 409：同一尝试被两个人同时复核时，只有第一个人生效，
     * 第二个人必须刷新后看到既有结论，不能覆盖。</p>
     */
    @Transactional
    public ReviewResult review(UUID attemptId, TrainingReviewRequest request) {
        if (!TrainingReview.isValidAction(request.getAction())) {
            throw new BusinessException(400, "复核动作只能是 pass 或 reject");
        }
        String reason = request.getReason() == null ? "" : request.getReason().trim();
        if (reason.isEmpty()) {
            throw new BusinessException(400, "复核理由不能为空");
        }
        if (TrainingReview.ACTION_REJECT.equals(request.getAction())
                && reason.length() < MIN_REJECT_REASON_CHARS) {
            // 退回理由就是学员的整改要求，不能只写「x」
            throw new BusinessException(400, "退回理由需写清具体整改要求（至少 "
                    + MIN_REJECT_REASON_CHARS + " 个字）");
        }

        TrainingAttempt attempt = requireAttempt(attemptId);
        TrainingTaskRecord record = requireRecord(attempt.getTaskRecordId());
        assertLatestRound(record);
        TrainingAssignment assignment = requireAssignment(record.getAssignmentId());
        accessService.assertCanReview(assignment.getCohortId());

        if (!companyScopeService.isUnrestrictedDataScope()
                && Objects.equals(TokenContext.get(), assignment.getLearnerId())) {
            throw new BusinessException(403, "不能复核自己的培训");
        }
        if (!assignment.isOpen()) {
            // 已取消/已认证的分配不再接受复核，避免继续写计数
            throw new BusinessException(409, "TRAINING_ASSIGNMENT_CLOSED", "该培训已结束，不能再复核");
        }
        String fromStatus = attempt.getStatus();
        // pending_review：AI 判分需确认；error：AI 评分彻底失败，由人工看证据裁决
        if (!TrainingAttempt.STATUS_PENDING_REVIEW.equals(fromStatus)
                && !TrainingAttempt.STATUS_ERROR.equals(fromStatus)) {
            throw new BusinessException(409, "TRAINING_REVIEW_STATE_INVALID", "该尝试不在可复核状态，请刷新后重试");
        }

        boolean pass = TrainingReview.ACTION_PASS.equals(request.getAction());
        String result = pass ? TrainingAttempt.RESULT_PASS : TrainingAttempt.RESULT_FAIL;
        LocalDateTime now = LocalDateTime.now();
        int updated = attemptRepository.finalizeByReview(attemptId,
                fromStatus, TrainingAttempt.STATUS_FINALIZED, result, now);
        if (updated == 0) {
            throw new BusinessException(409, "TRAINING_REVIEW_STATE_CHANGED", "复核状态已变更，请刷新后重试");
        }

        TrainingReview review = TrainingReview.builder()
                .id(UUID.randomUUID())
                .attemptId(attemptId)
                .reviewerId(TokenContext.get())
                .action(request.getAction())
                .reason(reason)
                .originalScore(attempt.getScore())
                .adjustedScore(writeJson(request.getAdjustedScore()))
                .createdAt(now)
                .build();
        reviewRepository.save(review);
        progressService.applyRecordResult(attempt.getTaskRecordId(), pass);

        attempt.setStatus(TrainingAttempt.STATUS_FINALIZED);
        attempt.setResult(result);
        attempt.setFinishedAt(now);
        String recordStatus = pass ? TrainingTaskRecord.STATUS_PASSED
                : TrainingTaskRecord.STATUS_FAILED;
        notificationService.notifyLearner(assignment,
                com.aiextract.model.UserNotification.TYPE_REVIEW_RESULT,
                pass ? "复核已通过" : "复核已退回",
                pass ? "主管复核通过：" + reason : "主管退回：" + reason,
                "/training/attempts/" + attemptId + "/result",
                "training:review:" + review.getId());
        log.info("培训复核完成 attemptId={} action={} reviewer={} learner={}",
                attemptId, request.getAction(), TokenContext.get(), assignment.getLearnerId());
        return new ReviewResult(attempt, review, recordStatus);
    }

    // ==================== 归属链 ====================

    /** 归属链统一 404 文案：不同环节用不同文案会暴露「这个 UUID 是否真实存在」 */
    private static final String NOT_FOUND = "培训数据不存在或不可访问";

    /** 补训后旧轮次不允许再复核/重跑评分（P1-7）。 */
    private void assertLatestRound(TrainingTaskRecord record) {
        int maxRound = recordRepository.findMaxRoundNo(record.getAssignmentId(), record.getTaskId());
        int round = record.getRoundNo() == null ? 1 : record.getRoundNo();
        if (round < maxRound) {
            throw BusinessException.conflict("TRAINING_OLD_ROUND",
                    "该任务已安排第 " + maxRound + " 轮补训，旧轮次不能再复核");
        }
    }

    private TrainingAttempt requireAttempt(UUID attemptId) {
        return attemptRepository.findById(attemptId)
                .orElseThrow(() -> new BusinessException(404, NOT_FOUND));
    }

    private TrainingTaskRecord requireRecord(UUID recordId) {
        return recordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(404, NOT_FOUND));
    }

    private TrainingAssignment requireAssignment(UUID assignmentId) {
        return assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new BusinessException(404, NOT_FOUND));
    }

    /**
     * 重跑评分：只接受 error 状态，重置重试计数与租约。
     *
     * <p>用于模型服务恢复后按同一份证据重评，避免学员为一次临时故障重做整场陪练。</p>
     */
    @Transactional
    public TrainingAttempt regrade(UUID attemptId) {
        TrainingAttempt attempt = requireAttempt(attemptId);
        TrainingTaskRecord record = requireRecord(attempt.getTaskRecordId());
        assertLatestRound(record);
        TrainingAssignment assignment = requireAssignment(record.getAssignmentId());
        accessService.assertCanReview(assignment.getCohortId());
        // 自审守卫：学员不能触发自己尝试的重新评分（与复核一致）
        if (!companyScopeService.isUnrestrictedDataScope()
                && Objects.equals(TokenContext.get(), assignment.getLearnerId())) {
            throw new BusinessException(403, "不能重跑自己培训的评分");
        }
        if (!assignment.isOpen()) {
            throw new BusinessException(409, "TRAINING_ASSIGNMENT_CLOSED", "该培训已结束，不能重跑评分");
        }
        if (!TrainingAttempt.STATUS_ERROR.equals(attempt.getStatus())) {
            throw new BusinessException(409, "TRAINING_REGRADE_NOT_ALLOWED", "只有评分失败的尝试可以重跑评分");
        }
        int updated = attemptRepository.resetForRegrade(attemptId);
        if (updated == 0) {
            throw new BusinessException(409, "TRAINING_REGRADE_STATE_CHANGED", "状态已变更，请刷新后重试");
        }
        attempt.setStatus(TrainingAttempt.STATUS_SUBMITTED);
        attempt.setGradingAttempts(0);
        attempt.setErrorMessage(null);
        attempt.setFinishedAt(null);
        log.info("培训评分已重新排队 attemptId={} operator={}", attemptId, TokenContext.get());
        return attempt;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            // 与答题 JSON 同口径做上限保护，避免单请求把 jsonb 写爆
            if (json.length() > MAX_ADJUSTED_SCORE_CHARS) {
                throw new BusinessException(400, "复核后分数过大（上限 32KB）");
            }
            return json;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "复核后分数不是合法 JSON");
        }
    }
}
