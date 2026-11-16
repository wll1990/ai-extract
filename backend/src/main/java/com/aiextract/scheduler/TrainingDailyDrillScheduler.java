package com.aiextract.scheduler;

import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.service.TrainingNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 每日微练提醒 —— 让「3 分钟练一题」真正发生。
 *
 * <p>只提醒站内信（IM 渠道需要企业侧账号绑定与机器人凭据，后续接入）；
 * 同一进度行同一天只提醒一次，避免每次扫描都打扰学员。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-15
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingDailyDrillScheduler {

    private final TrainingTaskRecordRepository recordRepository;
    private final TrainingTaskRepository taskRepository;
    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingNotificationService notificationService;

    @Value("${app.training.daily-drill.enabled:true}")
    private boolean enabled;

    @Value("${app.training.daily-drill.batch-size:200}")
    private int batchSize;

    /** 轮转游标：每轮从上一批之后继续，避免永远只提醒最早的一批 */
    private int scanOffset;

    @Scheduled(fixedDelayString = "${app.training.daily-drill.interval-ms:21600000}",
            initialDelayString = "${app.training.daily-drill.initial-delay-ms:180000}")
    public void remind() {
        if (!enabled) {
            return;
        }
        List<UUID> ids;
        try {
            ids = recordRepository.findPendingDrillRecordIds(batchSize, scanOffset);
            scanOffset = ids.size() < batchSize ? 0 : scanOffset + ids.size();
        } catch (Exception e) {
            log.error("每日微练扫描失败", e);
            return;
        }
        int sent = 0;
        for (UUID recordId : ids) {
            try {
                if (remindOne(recordId)) {
                    sent++;
                }
            } catch (Exception e) {
                log.warn("每日微练提醒失败 recordId={}", recordId, e);
            }
        }
        if (sent > 0) {
            log.info("每日微练提醒已发送 count={}", sent);
        }
    }

    private boolean remindOne(UUID recordId) {
        TrainingTaskRecord record = recordRepository.findById(recordId).orElse(null);
        if (record == null) {
            return false;
        }
        TrainingTask task = taskRepository.findById(record.getTaskId()).orElse(null);
        TrainingAssignment assignment = assignmentRepository.findById(record.getAssignmentId())
                .orElse(null);
        if (task == null || assignment == null || !assignment.isOpen()) {
            return false;
        }
        notificationService.notifyLearner(assignment, com.aiextract.model.UserNotification.TYPE_DAILY_DRILL,
                "今日 3 分钟微练",
                "任务「" + task.getTitle() + "」还没完成，花 3 分钟练一题，AI 会给出即时反馈。",
                "/training/assignments/" + assignment.getId() + "/tasks/" + task.getId(),
                "training:daily-drill:" + recordId + ":" + LocalDate.now());
        return true;
    }
}
