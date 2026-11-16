package com.aiextract.service;

import com.aiextract.model.FollowUpTask;
import com.aiextract.model.UserNotification;
import com.aiextract.repository.FollowUpTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 任务提醒：条件 UPDATE 抢占后投递站内信，多实例幂等。
 */
@Service
@RequiredArgsConstructor
public class SalesTaskReminderService {

    private final FollowUpTaskRepository taskRepository;
    private final NotificationService notificationService;
    private final SalesTaskService taskService;
    private final LeadActivityService activityService;

    @Transactional
    public void remindDueSoon(UUID taskId) {
        LocalDateTime now = LocalDateTime.now();
        if (taskRepository.markDueReminded(taskId, now) == 0) {
            return;
        }
        FollowUpTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        String content = task.getDueAt() == null ? task.getTitle()
                : "任务：" + task.getTitle() + "；截止：" + task.getDueAt();
        boolean sent = notificationService.notifyNow(task.getOwnerId(), task.getCompanyId(),
                UserNotification.TYPE_SALES_TASK_DUE_SOON, "跟进任务即将到期",
                content, "/sales/tasks", "follow_up_task", task.getId(),
                "task_due_soon:" + task.getId() + ":" + task.getOwnerId() + ":" + task.getDueAt());
        if (!sent) {
            // 通知没写成功就回滚标志，下一轮继续尝试，避免提醒永久丢失
            taskRepository.resetDueReminded(taskId, now);
        }
    }

    @Transactional
    public void remindOverdue(UUID taskId) {
        LocalDateTime now = LocalDateTime.now();
        if (taskRepository.markOverdueNotified(taskId, now) == 0) {
            return;
        }
        FollowUpTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null || task.getDueAt() == null) {
            return;
        }
        long days = Math.max(1, Duration.between(task.getDueAt(), now).toDays() + 1);
        boolean sent = notificationService.notifyNow(task.getOwnerId(), task.getCompanyId(),
                UserNotification.TYPE_SALES_TASK_OVERDUE, "跟进任务已逾期",
                "任务：" + task.getTitle() + "；已逾期 " + days + " 天",
                "/sales/tasks", "follow_up_task", task.getId(),
                "task_overdue:" + task.getId() + ":" + task.getOwnerId() + ":" + task.getDueAt());
        if (!sent) {
            taskRepository.resetOverdueNotified(taskId, now);
        }
    }

    @Transactional
    public void autoEscalate(UUID taskId) {
        LocalDateTime now = LocalDateTime.now();
        if (taskRepository.markAutoEscalated(taskId, now) == 0) {
            return;
        }
        FollowUpTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        int sent = taskService.escalateNotifyNow(task, "逾期超过 72 小时自动升级");
        if (sent == 0) {
            // 没有管理员或投递失败：回滚升级标志，补管理员后还能再升级
            taskRepository.resetAutoEscalated(taskId, now);
            return;
        }
        if (task.getLeadId() != null) {
            activityService.recordSystem(task.getLeadId(), "task_escalated",
                    "任务「" + task.getTitle() + "」逾期自动升级", "follow_up_task", task.getId());
        }
    }
}
