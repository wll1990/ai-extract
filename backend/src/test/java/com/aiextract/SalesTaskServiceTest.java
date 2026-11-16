package com.aiextract;

import com.aiextract.config.TokenContext;
import com.aiextract.dto.TaskDraftRequest;
import com.aiextract.dto.TaskUpdateRequest;
import com.aiextract.dto.TaskView;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.FollowUpTask;
import com.aiextract.model.Lead;
import com.aiextract.model.ReviewReport;
import com.aiextract.model.ReviewSession;
import com.aiextract.model.User;
import com.aiextract.model.UserNotification;
import com.aiextract.repository.FollowUpTaskRepository;
import com.aiextract.repository.LeadRepository;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.LeadActivityService;
import com.aiextract.service.NotificationService;
import com.aiextract.service.SalesAccessService;
import com.aiextract.service.SalesAuditService;
import com.aiextract.service.SalesTaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesTaskServiceTest {

    @Mock private SalesAccessService access;
    @Mock private FollowUpTaskRepository taskRepository;
    @Mock private ReviewReportRepository reportRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private UserRepository userRepository;
    @Mock private SalesAuditService audit;
    @Mock private NotificationService notificationService;
    @Mock private LeadActivityService activityService;

    private SalesTaskService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SalesTaskService(access, taskRepository, reportRepository, leadRepository,
                userRepository, audit, notificationService, activityService);
        TokenContext.set(userId, companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    private ReviewSession session() {
        return ReviewSession.builder()
                .id(sessionId).companyId(companyId).reviewerId(userId)
                .coachSkillId(UUID.randomUUID()).status("report_ready")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private FollowUpTask task(String status, LocalDateTime dueAt) {
        return FollowUpTask.builder()
                .id(UUID.randomUUID()).companyId(companyId).reviewId(UUID.randomUUID())
                .ownerId(userId).title("确认预算").channel("电话").content("问预算范围")
                .dueAt(dueAt).status(status).createdBy(userId)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private User user(UUID id, String name) {
        return User.builder().id(id).companyId(companyId).name(name).role("employee")
                .isActive(true).build();
    }

    private ReviewReport readyReport(String status) {
        return ReviewReport.builder()
                .id(UUID.randomUUID()).companyId(companyId).sessionId(sessionId)
                .version(1).status(status).attempts(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    @Test
    void createRejectsMoreThanThree() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createFromReview(sessionId, List.of(
                        new TaskDraftRequest("a", null, null, null),
                        new TaskDraftRequest("b", null, null, null),
                        new TaskDraftRequest("c", null, null, null),
                        new TaskDraftRequest("d", null, null, null))));
        assertEquals(400, ex.getErrorCode());
    }

    @Test
    void createSkipsDuplicateTitleInOneRequest() {
        when(access.requireSession(sessionId)).thenReturn(session());
        when(reportRepository.findFirstBySessionIdOrderByVersionDesc(sessionId))
                .thenReturn(Optional.of(readyReport("ready")));
        when(taskRepository.existsByReviewIdAndTitle(any(), anyString())).thenReturn(false);

        java.util.Map<String, Object> result = service.createFromReview(sessionId, List.of(
                new TaskDraftRequest("同名", null, null, null),
                new TaskDraftRequest("同名", null, null, null)));

        assertEquals(1, result.get("created"));
        assertEquals(List.of("同名"), result.get("skipped"));
    }

    @Test
    void createRejectsPastDue() {
        when(access.requireSession(sessionId)).thenReturn(session());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createFromReview(sessionId, List.of(
                        new TaskDraftRequest("任务", null, LocalDateTime.now().minusHours(1), null))));
        assertEquals(400, ex.getErrorCode());
    }

    @Test
    void createMapsUniqueConflictTo409() {
        when(access.requireSession(sessionId)).thenReturn(session());
        when(reportRepository.findFirstBySessionIdOrderByVersionDesc(sessionId))
                .thenReturn(Optional.of(readyReport("ready")));
        when(taskRepository.existsByReviewIdAndTitle(any(), anyString())).thenReturn(false);
        when(taskRepository.saveAllAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createFromReview(sessionId, List.of(
                        new TaskDraftRequest("任务", null, null, null))));
        assertEquals("TASK_DUPLICATE", ex.getSubCode());
    }

    @Test
    void createSuccessWritesLeadActivity() {
        ReviewSession s = session();
        s.setLeadId(UUID.randomUUID());
        when(access.requireSession(sessionId)).thenReturn(s);
        when(reportRepository.findFirstBySessionIdOrderByVersionDesc(sessionId))
                .thenReturn(Optional.of(readyReport("ready")));
        when(taskRepository.existsByReviewIdAndTitle(any(), anyString())).thenReturn(false);

        java.util.Map<String, Object> result = service.createFromReview(sessionId, List.of(
                new TaskDraftRequest("任务一", "电话", LocalDateTime.now().plusDays(1), "话术")));

        assertEquals(1, result.get("created"));
        assertEquals(java.util.List.of(), result.get("skipped"));
        verify(taskRepository).saveAllAndFlush(any());
        verify(activityService).record(eq(s.getLeadId()), eq("task_created"),
                anyString(), eq("review_session"), eq(sessionId));
    }

    @Test
    void completingTaskClosesLeadLoop() {
        UUID leadId = UUID.randomUUID();
        FollowUpTask t = task("pending", LocalDateTime.now().plusDays(1));
        t.setLeadId(leadId);
        Lead lead = Lead.builder().id(leadId).companyId(companyId).ownerId(userId)
                .name("华东制造").nameNormalized("华东制造").source("review")
                .status("following").createdBy(userId)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(access.requireTask(t.getId())).thenReturn(t);
        when(leadRepository.findById(leadId)).thenReturn(Optional.of(lead));
        when(taskRepository.findNextPendingDueAt(leadId)).thenReturn(null);

        service.updateStatus(t.getId(), "done");

        assertEquals("done", t.getStatus());
        assertNotNull(lead.getLastActivityAt());
        assertNull(lead.getNextFollowUpAt());
        verify(leadRepository).save(lead);
        verify(activityService).record(eq(leadId), eq("task_completed"),
                anyString(), eq("follow_up_task"), eq(t.getId()));
    }

    @Test
    void createRejectsWhenLatestReportNotReady() {
        when(access.requireSession(sessionId)).thenReturn(session());
        when(taskRepository.existsByReviewIdAndTitle(any(), anyString())).thenReturn(false);
        when(reportRepository.findFirstBySessionIdOrderByVersionDesc(sessionId))
                .thenReturn(Optional.of(readyReport("generating")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createFromReview(sessionId, List.of(
                        new TaskDraftRequest("任务", null, null, null))));
        assertEquals("REPORT_NOT_READY", ex.getSubCode());
        verify(taskRepository, never()).saveAllAndFlush(any());
    }

    @Test
    void updateOverdueTaskWithoutChangingDueDateIsAllowed() {
        LocalDateTime past = LocalDateTime.now().minusDays(2);
        FollowUpTask t = task("pending", past);
        when(access.requireTask(t.getId())).thenReturn(t);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "张伟")));

        TaskView view = service.update(t.getId(),
                new TaskUpdateRequest("确认预算范围", "微信", past, "改话术"));

        assertEquals("确认预算范围", view.title());
        assertEquals("微信", view.channel());
        verify(taskRepository).saveAndFlush(t);
    }

    @Test
    void updateRejectsNewPastDue() {
        FollowUpTask t = task("pending", LocalDateTime.now().plusDays(1));
        when(access.requireTask(t.getId())).thenReturn(t);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(t.getId(), new TaskUpdateRequest(
                        "任务", null, LocalDateTime.now().minusHours(1), null)));
        assertEquals(400, ex.getErrorCode());
    }

    @Test
    void updateCompletedTaskRejected() {
        FollowUpTask t = task("done", LocalDateTime.now());
        when(access.requireTask(t.getId())).thenReturn(t);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(t.getId(), new TaskUpdateRequest("任务", null, null, null)));
        assertEquals("TASK_STATE_INVALID", ex.getSubCode());
    }

    @Test
    void transferRejectsSelf() {
        FollowUpTask t = task("pending", LocalDateTime.now());
        when(access.requireTask(t.getId())).thenReturn(t);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, "张伟")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.transfer(t.getId(), userId));
        assertEquals(400, ex.getErrorCode());
        verify(notificationService, never()).notifyUser(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void transferNotifiesTargetAndWritesAudit() {
        FollowUpTask t = task("pending", LocalDateTime.now());
        UUID targetId = UUID.randomUUID();
        when(access.requireTask(t.getId())).thenReturn(t);
        when(userRepository.findById(targetId)).thenReturn(Optional.of(user(targetId, "李娜")));

        TaskView view = service.transfer(t.getId(), targetId);

        assertEquals(targetId, view.ownerId());
        verify(notificationService).notifyUser(eq(targetId), eq(companyId),
                eq(UserNotification.TYPE_SALES_TASK_TRANSFERRED), anyString(), anyString(),
                eq("/sales/tasks"), eq("follow_up_task"), eq(t.getId()), anyString());
        verify(audit).record(eq("sales.task.transfer"), eq("follow_up_task"),
                eq(t.getId()), any());
    }

    @Test
    void escalateTwiceRejected() {
        FollowUpTask t = task("pending", LocalDateTime.now());
        t.setEscalatedAt(LocalDateTime.now());
        when(access.requireTask(t.getId())).thenReturn(t);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.escalate(t.getId(), "催一下"));
        assertEquals("TASK_ALREADY_ESCALATED", ex.getSubCode());
    }

    @Test
    void ownersUsesCompanyPicker() {
        when(access.requireCompanyId()).thenReturn(companyId);
        when(userRepository.searchForPicker(eq(companyId), any(), eq("李"),
                any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(user(UUID.randomUUID(), "李娜"))));

        var page = service.owners("李", 1, 20);

        assertEquals(1, page.getTotalElements());
        assertEquals("李娜", page.getContent().get(0).name());
    }
}
