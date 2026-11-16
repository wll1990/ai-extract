package com.aiextract.scheduler;

import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.UserNotification;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.service.TrainingNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 待认证滞留提醒 —— 学员必修做完了，主管却一直没确认，培训永远收不了口。
 *
 * <p>进入待认证时已经通知过一次；这里对超过阈值仍未确认的分配按批次汇总再提醒
 * 导师/管理员，同一批次同一天只提醒一次（dedupeKey 去重）。
 * 只提醒，不自动认证：认证是主管判定，不能由超时替代。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingPendingCertificationSweeper {

    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingNotificationService notificationService;

    @Value("${app.training.pending-certification.enabled:true}")
    private boolean enabled;

    @Value("${app.training.pending-certification.threshold-hours:48}")
    private long thresholdHours;

    @Value("${app.training.pending-certification.batch-size:100}")
    private int batchSize;

    /** 轮转游标：每轮从上一批之后继续，避免永远只扫最老的记录 */
    private int scanOffset;

    @Scheduled(fixedDelayString = "${app.training.pending-certification.interval-ms:3600000}",
            initialDelayString = "${app.training.pending-certification.initial-delay-ms:120000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        LocalDateTime before = LocalDateTime.now().minusHours(Math.max(thresholdHours, 1));
        List<UUID> ids;
        try {
            ids = assignmentRepository.findStalePendingCertificationIds(
                    before, batchSize, scanOffset);
            scanOffset = ids.size() < batchSize ? 0 : scanOffset + ids.size();
        } catch (Exception e) {
            log.error("待认证滞留扫描失败", e);
            return;
        }
        Map<UUID, Integer> staleByCohort = new LinkedHashMap<>();
        for (UUID id : ids) {
            try {
                collect(id, staleByCohort);
            } catch (Exception e) {
                log.warn("待认证滞留扫描失败 assignmentId={}", id, e);
            }
        }
        staleByCohort.forEach(this::notifyCohort);
    }

    private void collect(UUID assignmentId, Map<UUID, Integer> staleByCohort) {
        TrainingAssignment assignment = assignmentRepository.findById(assignmentId).orElse(null);
        if (assignment == null || !assignment.isOpen()
                || !TrainingAssignment.STATUS_PENDING_CERTIFICATION.equals(assignment.getStatus())) {
            return;
        }
        staleByCohort.merge(assignment.getCohortId(), 1, Integer::sum);
    }

    private void notifyCohort(UUID cohortId, Integer count) {
        notificationService.notifyReviewerByCohortId(cohortId,
                UserNotification.TYPE_PENDING_CERTIFICATION,
                "有 " + count + " 名学员待认证",
                "本批次有 " + count + " 名学员已完成必修超过 "
                        + Math.max(thresholdHours, 1) + " 小时仍未确认认证，请尽快处理。",
                "/admin/training/cohorts/" + cohortId,
                "training:pending-cert-sweep:" + cohortId + ":" + LocalDate.now());
        log.info("待认证滞留提醒 cohortId={} count={}", cohortId, count);
    }
}
