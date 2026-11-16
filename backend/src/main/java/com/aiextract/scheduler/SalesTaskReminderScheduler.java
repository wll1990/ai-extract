package com.aiextract.scheduler;

import com.aiextract.repository.FollowUpTaskRepository;
import com.aiextract.service.SalesTaskReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 跟进任务提醒：到期前 24h、逾期、逾期 72h 自动升级。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesTaskReminderScheduler {

    private final FollowUpTaskRepository taskRepository;
    private final SalesTaskReminderService reminderService;

    @Value("${app.sales.reminder.enabled:true}")
    private boolean enabled;

    @Value("${app.sales.reminder.batch-size:200}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.sales.reminder.interval-ms:600000}",
            initialDelayString = "${app.sales.reminder.initial-delay-ms:60000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (UUID id : taskRepository.findDueSoonIds(now, now.plusHours(24), batchSize)) {
            try {
                reminderService.remindDueSoon(id);
            } catch (Exception e) {
                log.warn("任务到期前提醒失败 taskId={}", id, e);
            }
        }
        for (UUID id : taskRepository.findOverdueIds(now, batchSize)) {
            try {
                reminderService.remindOverdue(id);
            } catch (Exception e) {
                log.warn("任务逾期提醒失败 taskId={}", id, e);
            }
        }
        for (UUID id : taskRepository.findEscalationCandidates(now.minusHours(72), batchSize)) {
            try {
                reminderService.autoEscalate(id);
            } catch (Exception e) {
                log.warn("任务自动升级失败 taskId={}", id, e);
            }
        }
    }
}
