package com.aiextract.service;

import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.UserNotification;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 评分结果落库 —— 只包状态与结果写入，**不含任何 AI / 网络调用**。
 *
 * <p>事务边界刻意压到最短：AI 判分在 {@link TrainingGradingService} 里事务外完成，
 * 这里只做一次条件 UPDATE + 一次进度推进，持有时间 < 100ms。</p>
 *
 * <p>条件 UPDATE 的 rowcount 是唯一可信的「我是否仍持有该行」判据：
 * 租约过期被其他 worker 接管时返回 0，此时绝不能再去改进度行。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingGradingStateService {

    private static final int ERROR_MESSAGE_MAX_CHARS = 1000;

    private final TrainingAttemptRepository attemptRepository;
    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingProgressService progressService;
    private final TrainingNotificationService notificationService;

    /**
     * 判分完成：写状态/结论/评分，并推进任务进度。
     *
     * @return false 表示租约已被他人接管或状态已变，本次结果作废
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean complete(UUID attemptId, UUID recordId, String owner,
                            String status, String result, String scoreJson) {
        TrainingAttempt attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null) {
            return false;
        }
        TrainingAssignment assignment = assignmentRepository.findById(attempt.getAssignmentId())
                .orElse(null);
        if (assignment == null || !assignment.isOpen()) {
            // 取消/认证后迟到的评分结果不能再生效，也不能转入待复核
            attemptRepository.markError(attemptId, TrainingAttempt.STATUS_GRADING, owner,
                    TrainingAttempt.STATUS_ERROR, "培训已结束，评分结果作废", LocalDateTime.now());
            log.warn("评分结果作废（培训已结束） attemptId={}", attemptId);
            return false;
        }
        int updated = attemptRepository.completeGrading(attemptId,
                TrainingAttempt.STATUS_GRADING, owner, status, result, scoreJson, LocalDateTime.now());
        if (updated == 0) {
            // 租约已被他人接管：本次 AI 结果作废，绝不能再去推进度
            log.warn("评分结果作废（租约已失效或状态已变） attemptId={} owner={} targetStatus={}",
                    attemptId, owner, status);
            return false;
        }
        if (TrainingAttempt.STATUS_FINALIZED.equals(status)) {
            progressService.applyRecordResult(recordId, TrainingAttempt.RESULT_PASS.equals(result));
            if (!TrainingAttempt.RESULT_PASS.equals(result)) {
                notifyLearner(attemptId, UserNotification.TYPE_REVIEW_RESULT, "任务未通过",
                        "本次尝试已出分：未通过，可在任务页查看原因或按主管要求补训。",
                        "training:graded:");
            }
        } else if (TrainingAttempt.STATUS_PENDING_REVIEW.equals(status)) {
            progressService.markPendingReview(recordId);
            notifyReviewer(attemptId, "有待复核的尝试",
                    "学员交卷后进入待复核，请尽快裁决。", "training:pending-review:");
        }
        return true;
    }

    /**
     * 评分失败：未到重试上限则退回 submitted 等下一轮领取，达到上限才落 error。
     *
     * <p>{@code gradingAttempts} 在领取时自增，所以这里比较的是「已执行次数」。
     * 落 error 不产生任何业务结论，学员可以重新开始一次尝试（error 不占 max_attempts）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void failOrRequeue(UUID attemptId, String owner, int maxAttempts, String message) {
        TrainingAttempt attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null || !TrainingAttempt.STATUS_GRADING.equals(attempt.getStatus())
                || !owner.equals(attempt.getLeaseOwner())) {
            return; // 租约已不属于本 worker
        }
        String safeMessage = truncate(message);
        TrainingAssignment assignment = assignmentRepository.findById(attempt.getAssignmentId())
                .orElse(null);
        if (assignment == null || !assignment.isOpen()) {
            attemptRepository.markError(attemptId, TrainingAttempt.STATUS_GRADING, owner,
                    TrainingAttempt.STATUS_ERROR, "培训已结束，评分结果作废", LocalDateTime.now());
            return;
        }
        int attempts = attempt.getGradingAttempts() == null ? 0 : attempt.getGradingAttempts();
        if (attempts < Math.max(1, maxAttempts)) {
            attemptRepository.requeue(attemptId, TrainingAttempt.STATUS_GRADING, owner,
                    TrainingAttempt.STATUS_SUBMITTED, safeMessage);
            log.warn("评分失败，等待重试 attemptId={} attempts={}/{} reason={}",
                    attemptId, attempts, maxAttempts, safeMessage);
        } else {
            int updated = attemptRepository.markError(attemptId, TrainingAttempt.STATUS_GRADING,
                    owner, TrainingAttempt.STATUS_ERROR, safeMessage, LocalDateTime.now());
            if (updated > 0) {
                // AI 评分彻底失败：进度行转待复核，给主管一个人工裁决入口；
                // 否则学员会永久卡在 in_progress，认证链路永远走不到
                progressService.markPendingReview(attempt.getTaskRecordId());
                notifyReviewer(attemptId, "有评分失败的尝试待裁决",
                        "AI 评分连续失败，学员已转人工复核，请查看证据后裁决。",
                        "training:grading-error:");
            }
            log.error("评分失败已达上限，落 error 并转人工复核 attemptId={} attempts={} reason={}",
                    attemptId, attempts, safeMessage);
        }
    }

    private void notifyLearner(UUID attemptId, String type, String title,
                               String content, String dedupePrefix) {
        TrainingAssignment assignment = assignmentOf(attemptId);
        if (assignment == null) {
            return;
        }
        notificationService.notifyLearner(assignment, type, title, content,
                "/training", dedupePrefix + attemptId);
    }

    private void notifyReviewer(UUID attemptId, String title, String content, String dedupePrefix) {
        TrainingAssignment assignment = assignmentOf(attemptId);
        if (assignment == null) {
            return;
        }
        notificationService.notifyReviewerByCohortId(assignment.getCohortId(),
                UserNotification.TYPE_PENDING_REVIEW, title, content,
                "/admin/training/cohorts/" + assignment.getCohortId(),
                dedupePrefix + attemptId);
    }

    private TrainingAssignment assignmentOf(UUID attemptId) {
        return attemptRepository.findById(attemptId)
                .flatMap(attempt -> assignmentRepository.findById(attempt.getAssignmentId()))
                .orElse(null);
    }

    private String truncate(String message) {
        String value = message == null || message.isBlank() ? "评分失败" : message;
        return value.length() > ERROR_MESSAGE_MAX_CHARS
                ? value.substring(0, ERROR_MESSAGE_MAX_CHARS) + "…"
                : value;
    }
}
