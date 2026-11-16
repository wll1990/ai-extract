package com.aiextract.scheduler;

import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.UserNotification;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.service.TrainingNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * pending_review / error 滞留监控 —— 只提醒，不自动定稿。
 *
 * <p>成绩不能由超时决定（docs/50）；超过阈值的尝试每天提醒一次导师/管理员，
 * 同一尝试同日通过 dedupeKey 去重。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingStaleReviewSweeper {

    private final TrainingAttemptRepository attemptRepository;
    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingNotificationService notificationService;

    @Value("${app.training.pending-review.enabled:true}")
    private boolean enabled;

    @Value("${app.training.pending-review.threshold-hours:24}")
    private long thresholdHours;

    @Value("${app.training.pending-review.batch-size:50}")
    private int batchSize;

    /** 轮转游标：每轮从上一批之后继续，避免永远只扫最老的 50 条 */
    private int scanOffset;

    @Scheduled(fixedDelayString = "${app.training.pending-review.interval-ms:300000}",
            initialDelayString = "${app.training.pending-review.initial-delay-ms:60000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        LocalDateTime before = LocalDateTime.now().minusHours(Math.max(thresholdHours, 1));
        List<UUID> ids;
        try {
            ids = attemptRepository.findStaleReviewIds(before, batchSize, scanOffset);
            // 已扫到末尾则下一轮从头开始；否则继续向后
            scanOffset = ids.size() < batchSize ? 0 : scanOffset + ids.size();
        } catch (Exception e) {
            log.error("待复核滞留扫描失败", e);
            return;
        }
        // 按批次聚合：逐条通知会让一个导师一天收到几十条同样的提醒（大班刷屏）
        java.util.Map<UUID, Integer> staleByCohort = new java.util.LinkedHashMap<>();
        for (UUID attemptId : ids) {
            try {
                collect(attemptId, staleByCohort);
            } catch (Exception e) {
                log.warn("待复核滞留扫描失败 attemptId={}", attemptId, e);
            }
        }
        staleByCohort.forEach(this::notifyCohort);
    }

    /** 汇总每个批次仍处于待处理状态的尝试数；已裁决的尝试不再提醒 */
    private void collect(UUID attemptId, java.util.Map<UUID, Integer> staleByCohort) {
        TrainingAttempt attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null) {
            return;
        }
        String status = attempt.getStatus();
        if (!TrainingAttempt.STATUS_PENDING_REVIEW.equals(status)
                && !TrainingAttempt.STATUS_ERROR.equals(status)) {
            return;
        }
        TrainingAssignment assignment = assignmentRepository.findById(attempt.getAssignmentId())
                .orElse(null);
        if (assignment == null || !assignment.isOpen()) {
            // 已取消/已认证的分配不能再产生「待复核」提醒
            return;
        }
        staleByCohort.merge(assignment.getCohortId(), 1, Integer::sum);
    }

    private void notifyCohort(UUID cohortId, Integer count) {
        notificationService.notifyReviewerByCohortId(cohortId,
                UserNotification.TYPE_STALE_REVIEW,
                "有 " + count + " 条培训尝试待处理",
                "本批次有 " + count + " 条待复核/评分失败的尝试已超过 "
                        + Math.max(thresholdHours, 1) + " 小时未处理，请尽快裁决。",
                "/admin/training/cohorts/" + cohortId,
                // 批次 + 日期去重：同一天同一批次只提醒一次
                "training:stale-review:" + cohortId + ":" + LocalDate.now());
        log.info("待复核滞留提醒 cohortId={} count={}", cohortId, count);
    }
}
