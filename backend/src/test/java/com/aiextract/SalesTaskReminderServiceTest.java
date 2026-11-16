package com.aiextract;

import com.aiextract.model.FollowUpTask;
import com.aiextract.model.UserNotification;
import com.aiextract.repository.FollowUpTaskRepository;
import com.aiextract.service.LeadActivityService;
import com.aiextract.service.NotificationService;
import com.aiextract.service.SalesTaskReminderService;
import com.aiextract.service.SalesTaskService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesTaskReminderServiceTest {

    @Mock private FollowUpTaskRepository taskRepository;
    @Mock private NotificationService notificationService;
    @Mock private SalesTaskService taskService;
    @Mock private LeadActivityService activityService;

    private SalesTaskReminderService service() {
        return new SalesTaskReminderService(taskRepository, notificationService,
                taskService, activityService);
    }

    private FollowUpTask task() {
        UUID companyId = UUID.randomUUID();
        return FollowUpTask.builder()
                .id(UUID.randomUUID()).companyId(companyId).ownerId(UUID.randomUUID())
                .leadId(UUID.randomUUID()).title("确认预算").status("pending")
                .dueAt(LocalDateTime.now().plusHours(2))
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    @Test
    void claimLoserDoesNotNotify() {
        UUID id = UUID.randomUUID();
        when(taskRepository.markDueReminded(eq(id), any())).thenReturn(0);

        service().remindDueSoon(id);

        verify(taskRepository, never()).findById(any());
        verify(notificationService, never()).notifyNow(any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void dueSoonNotifiesOwnerOnce() {
        FollowUpTask t = task();
        when(taskRepository.markDueReminded(eq(t.getId()), any())).thenReturn(1);
        when(taskRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(notificationService.notifyNow(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        service().remindDueSoon(t.getId());

        verify(notificationService).notifyNow(eq(t.getOwnerId()), eq(t.getCompanyId()),
                eq(UserNotification.TYPE_SALES_TASK_DUE_SOON), anyString(), anyString(),
                eq("/sales/tasks"), eq("follow_up_task"), eq(t.getId()), anyString());
        verify(taskRepository, never()).resetDueReminded(any(), any());
    }

    @Test
    void dueSoonNotificationFailureResetsFlagForRetry() {
        FollowUpTask t = task();
        when(taskRepository.markDueReminded(eq(t.getId()), any())).thenReturn(1);
        when(taskRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(notificationService.notifyNow(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        service().remindDueSoon(t.getId());

        verify(taskRepository).resetDueReminded(eq(t.getId()), any());
    }

    @Test
    void autoEscalateNotifiesAdminsAndWritesSystemActivity() {
        FollowUpTask t = task();
        t.setDueAt(LocalDateTime.now().minusDays(4));
        when(taskRepository.markAutoEscalated(eq(t.getId()), any())).thenReturn(1);
        when(taskRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(taskService.escalateNotifyNow(eq(t), anyString())).thenReturn(1);

        service().autoEscalate(t.getId());

        verify(taskService).escalateNotifyNow(eq(t), anyString());
        verify(activityService).recordSystem(eq(t.getLeadId()), eq("task_escalated"),
                anyString(), eq("follow_up_task"), eq(t.getId()));
    }

    @Test
    void autoEscalateWithoutAdminResetsFlag() {
        FollowUpTask t = task();
        when(taskRepository.markAutoEscalated(eq(t.getId()), any())).thenReturn(1);
        when(taskRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(taskService.escalateNotifyNow(eq(t), anyString())).thenReturn(0);

        service().autoEscalate(t.getId());

        verify(taskRepository).resetAutoEscalated(eq(t.getId()), any());
        verify(activityService, never()).recordSystem(any(), any(), any(), any(), any());
    }
}
