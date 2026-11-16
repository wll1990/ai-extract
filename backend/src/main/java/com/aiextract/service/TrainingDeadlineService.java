package com.aiextract.service;

import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 逾期处理 —— 按任务配置的 `deadline_policy` 结束进行中的尝试。
 *
 * <ul>
 *   <li>{@code auto_submit}：代交（in_progress → submitted），由评分 worker 正常判分；</li>
 *   <li>{@code timeout_fail}：转人工复核（in_progress → pending_review，建议 fail）。
 *       超时结论不能由系统一句话定死，必须给主管一次裁决机会。</li>
 * </ul>
 *
 * <p>只处理「扫描器选出来的、仍处于 in_progress」的行：学员恰好先提交时条件更新命中 0 行，
 * 不会覆盖正常流程。单个尝试一个短事务，事务内无 AI/网络调用。</p>
 *
 * <p>无法处理的行（任务/记录缺失、分配已取消/已认证）置 error 移出队列，
 * 避免脏行永久占据每轮 LIMIT 名额。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingDeadlineService {

    private final TrainingAttemptRepository attemptRepository;
    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingTaskRepository taskRepository;
    private final TrainingTaskRecordRepository recordRepository;
    private final TrainingProgressService progressService;
    private final TrainingNotificationService notificationService;

    /** 处理一个已逾期的尝试；幂等，重复调用无副作用 */
    @Transactional(rollbackFor = Exception.class)
    public void handleOverdue(UUID attemptId) {
        TrainingAttempt attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null || !TrainingAttempt.STATUS_IN_PROGRESS.equals(attempt.getStatus())) {
            return;
        }
        TrainingAssignment assignment = assignmentRepository
                .findById(attempt.getAssignmentId()).orElse(null);
        if (assignment == null || !assignment.isOpen()) {
            // 取消/已认证的分配不应再产生成绩与计数副作用
            attemptRepository.markSweepError(attemptId, "培训分配已结束，逾期处理跳过");
            log.warn("逾期跳过：分配已结束 attemptId={} assignmentId={}",
                    attemptId, attempt.getAssignmentId());
            return;
        }
        // 二次校验有效截止：扫描器选中后可能已被延期，不能按旧截止处理
        LocalDateTime now = LocalDateTime.now();
        if (attempt.getDeadline() != null) {
            if (attempt.getDeadline().isAfter(now)) {
                log.debug("逾期跳过：考试已延期 attemptId={} deadline={}", attemptId, attempt.getDeadline());
                return;
            }
        } else if (assignment.getDeadline() == null || assignment.getDeadline().isAfter(now)) {
            log.debug("逾期跳过：陪练已延期 assignmentId={} deadline={}",
                    assignment.getId(), assignment.getDeadline());
            return;
        }

        TrainingTask task = taskRepository.findById(attempt.getTaskId()).orElse(null);
        if (task == null) {
            // 无法判断策略：置 error 移出队列，并让进度行进待复核，避免永久卡死
            attemptRepository.markSweepError(attemptId, "培训任务缺失，无法按策略处理");
            progressService.markPendingReview(attempt.getTaskRecordId());
            log.error("逾期处理失败：任务不存在 attemptId={} taskId={}",
                    attemptId, attempt.getTaskId());
            return;
        }
        if (!recordRepository.existsById(attempt.getTaskRecordId())) {
            attemptRepository.markSweepError(attemptId, "任务进度记录缺失，无法回写结果");
            log.error("逾期处理失败：进度记录不存在 attemptId={} recordId={}",
                    attemptId, attempt.getTaskRecordId());
            return;
        }

        if (TrainingTask.DEADLINE_TIMEOUT_FAIL.equals(task.getDeadlinePolicy())) {
            int updated = attemptRepository.markOverdueReview(attemptId);
            if (updated == 0) {
                return; // 学员刚提交/已被其他实例处理
            }
            progressService.markPendingReview(attempt.getTaskRecordId());
            notificationService.notifyLearner(assignment,
                    com.aiextract.model.UserNotification.TYPE_PENDING_REVIEW,
                    "任务已超时，等待主管复核",
                    "超过截止时间，本次尝试已转主管裁决，结论出来后可在成绩页查看。",
                    "/training", "training:timeout:" + attemptId);
            notificationService.notifyReviewerByCohortId(assignment.getCohortId(),
                    com.aiextract.model.UserNotification.TYPE_PENDING_REVIEW,
                    "有超时尝试待复核",
                    "学员「" + assignment.getLearnerId() + "」的考试已超时，请尽快裁决。",
                    "/admin/training/cohorts/" + assignment.getCohortId(),
                    "training:timeout-review:" + attemptId);
            log.info("逾期转人工复核 attemptId={} taskId={} policy=timeout_fail",
                    attemptId, attempt.getTaskId());
            return;
        }

        int updated = attemptRepository.submitOverdue(attemptId);
        if (updated > 0) {
            notificationService.notifyLearner(assignment,
                    com.aiextract.model.UserNotification.TYPE_REVIEW_RESULT,
                    "已到截止时间，系统自动交卷",
                    "本次尝试已自动提交并进入评分，可在成绩页查看结果。",
                    "/training", "training:auto-submit:" + attemptId);
            log.info("逾期自动交卷 attemptId={} taskId={} policy=auto_submit",
                    attemptId, attempt.getTaskId());
        }
    }
}
