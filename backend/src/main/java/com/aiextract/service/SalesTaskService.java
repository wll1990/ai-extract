package com.aiextract.service;

import com.aiextract.config.RolePermissions;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.TaskDraftRequest;
import com.aiextract.dto.TaskUpdateRequest;
import com.aiextract.dto.TaskView;
import com.aiextract.dto.UserOptionView;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 复盘「下一步」转跟进任务，以及任务编辑/转交/升级。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesTaskService {

    private final SalesAccessService access;
    private final FollowUpTaskRepository taskRepository;
    private final ReviewReportRepository reportRepository;
    private final LeadRepository leadRepository;
    private final UserRepository userRepository;
    private final SalesAuditService audit;
    private final NotificationService notificationService;
    private final LeadActivityService activityService;

    @Transactional
    public Map<String, Object> createFromReview(UUID sessionId, List<TaskDraftRequest> drafts) {
        ReviewSession session = access.requireSession(sessionId);
        access.requireLeadManage();
        if (drafts == null || drafts.isEmpty() || drafts.size() > 3) {
            throw new BusinessException(400, "请选择 1–3 条下一步");
        }
        LocalDateTime now = LocalDateTime.now();
        Set<String> titles = new HashSet<>();
        List<String> skipped = new ArrayList<>();
        List<FollowUpTask> tasks = new ArrayList<>();
        for (TaskDraftRequest d : drafts) {
            String title = d.title() == null ? "" : d.title().trim();
            if (title.isEmpty()) {
                throw new BusinessException(400, "任务标题不能为空");
            }
            // 同名任务逐条跳过，不因为一条冲突导致整批失败
            if (!titles.add(title)) {
                skipped.add(title);
                continue;
            }
            if (d.dueAt() != null && d.dueAt().isBefore(now)) {
                throw new BusinessException(400, "截止时间不能早于现在");
            }
            if (taskRepository.existsByReviewIdAndTitle(sessionId, title)) {
                skipped.add(title);
                continue;
            }
            tasks.add(FollowUpTask.builder()
                    .id(UUID.randomUUID()).companyId(session.getCompanyId())
                    .leadId(session.getLeadId()).reviewId(sessionId)
                    .ownerId(session.getReviewerId())
                    .title(title).channel(trimToNull(d.channel()))
                    .content(trimToNull(d.content())).dueAt(d.dueAt())
                    .status("pending").createdBy(TokenContext.get())
                    .createdAt(now).updatedAt(now).build());
        }
        // 只能基于最新版且已完成的报告建任务；旧版/失败版不可用（P1-6）
        ReviewReport latest = reportRepository.findFirstBySessionIdOrderByVersionDesc(sessionId).orElse(null);
        if (latest == null || !"ready".equals(latest.getStatus())) {
            throw BusinessException.conflict("REPORT_NOT_READY", "最新报告尚未生成完成，不能创建任务");
        }
        try {
            taskRepository.saveAllAndFlush(tasks);
        } catch (DataIntegrityViolationException e) {
            throw BusinessException.conflict("TASK_DUPLICATE", "任务已存在，请刷新后重试");
        }
        if (session.getLeadId() != null) {
            activityService.record(session.getLeadId(), "task_created",
                    "创建 " + tasks.size() + " 条跟进任务", "review_session", sessionId);
        }
        audit.record("sales.task.create", "review_session", sessionId,
                Map.of("created", tasks.size(), "skipped", skipped.size()));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("created", tasks.size());
        result.put("skipped", skipped);
        return result;
    }

    public Page<TaskView> list(int page, int size, String status) {
        UUID companyId = access.requireCompanyId();
        Pageable p = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 100));
        UUID me = TokenContext.get();
        String st = trimToNull(status);
        Page<FollowUpTask> rows = access.canViewAll()
                ? (st == null
                        ? taskRepository.findByCompanyIdOrderByDueAtAsc(companyId, p)
                        : taskRepository.findByCompanyIdAndStatusOrderByDueAtAsc(companyId, st, p))
                : (st == null
                        ? taskRepository.findByCompanyIdAndOwnerIdOrderByDueAtAsc(companyId, me, p)
                        : taskRepository.findByCompanyIdAndOwnerIdAndStatusOrderByDueAtAsc(
                                companyId, me, st, p));
        Map<UUID, String> names = loadNames(rows.getContent().stream()
                .map(FollowUpTask::getOwnerId).distinct().toList());
        return rows.map(t -> toView(t, names.get(t.getOwnerId())));
    }

    @Transactional
    public TaskView update(UUID id, TaskUpdateRequest req) {
        FollowUpTask task = access.requireTask(id);
        if (!"pending".equals(task.getStatus())) {
            throw BusinessException.conflict("TASK_STATE_INVALID", "已结束的任务不能编辑");
        }
        String title = req.title().trim();
        if (title.isEmpty()) {
            throw new BusinessException(400, "任务标题不能为空");
        }
        if (task.getReviewId() != null
                && taskRepository.existsByReviewIdAndTitleAndIdNot(task.getReviewId(), title, id)) {
            throw BusinessException.conflict("TASK_DUPLICATE", "已存在同名任务：" + title);
        }
        boolean dueChanged = !Objects.equals(task.getDueAt(), req.dueAt());
        if (dueChanged && req.dueAt() != null && req.dueAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(400, "截止时间不能早于现在");
        }
        task.setTitle(title);
        task.setChannel(trimToNull(req.channel()));
        task.setContent(trimToNull(req.content()));
        task.setDueAt(req.dueAt());
        if (dueChanged) {
            task.setDueRemindedAt(null);
            task.setOverdueNotifiedAt(null);
        }
        task.setUpdatedAt(LocalDateTime.now());
        try {
            // 唯一冲突必须在事务内 flush 才能转成 409，否则会在提交时变成 500
            taskRepository.saveAndFlush(task);
        } catch (DataIntegrityViolationException e) {
            throw BusinessException.conflict("TASK_DUPLICATE", "已存在同名任务，请刷新后重试");
        }
        audit.record("sales.task.update", "follow_up_task", id,
                Map.of("title", title, "dueChanged", dueChanged));
        return toView(task, ownerName(task.getOwnerId()));
    }

    @Transactional
    public TaskView transfer(UUID id, UUID newOwnerId) {
        FollowUpTask task = access.requireTask(id);
        access.requireLeadManage();
        if (!"pending".equals(task.getStatus())) {
            throw BusinessException.conflict("TASK_STATE_INVALID", "已结束的任务不能转交");
        }
        User target = userRepository.findById(newOwnerId)
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .filter(u -> Objects.equals(u.getCompanyId(), task.getCompanyId()))
                .filter(u -> List.of(RolePermissions.EMPLOYEE, RolePermissions.COMPANY_ADMIN,
                        RolePermissions.SUPER_ADMIN).contains(u.getRole()))
                .orElseThrow(() -> new BusinessException(400, "目标负责人不存在或不是本企业员工"));
        if (target.getId().equals(task.getOwnerId())) {
            throw new BusinessException(400, "任务已经在该负责人名下");
        }
        UUID from = task.getOwnerId();
        task.setOwnerId(target.getId());
        // 新负责人需要重新收到到期/逾期提醒
        task.setDueRemindedAt(null);
        task.setOverdueNotifiedAt(null);
        task.setUpdatedAt(LocalDateTime.now());
        taskRepository.save(task);
        notificationService.notifyUser(target.getId(), task.getCompanyId(),
                UserNotification.TYPE_SALES_TASK_TRANSFERRED, "你收到一条跟进任务",
                task.getTitle(), "/sales/tasks", "follow_up_task", task.getId(),
                "task_transfer:" + task.getId() + ":" + from + ":" + target.getId()
                        + ":" + System.currentTimeMillis());
        if (task.getLeadId() != null) {
            activityService.record(task.getLeadId(), "task_transferred",
                    "任务「" + task.getTitle() + "」转交给 " + target.getName(),
                    "follow_up_task", task.getId());
        }
        audit.record("sales.task.transfer", "follow_up_task", id,
                Map.of("from", from.toString(), "to", target.getId().toString()));
        return toView(task, target.getName());
    }

    @Transactional
    public TaskView escalate(UUID id, String reason) {
        FollowUpTask task = access.requireTask(id);
        access.requireLeadManage();
        if (!"pending".equals(task.getStatus())) {
            throw BusinessException.conflict("TASK_STATE_INVALID", "已结束的任务不能升级");
        }
        if (task.getEscalatedAt() != null) {
            throw BusinessException.conflict("TASK_ALREADY_ESCALATED", "任务已经升级过");
        }
        if (task.getDueAt() == null || !task.getDueAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(400, "任务尚未逾期，不能升级");
        }
        task.setEscalatedAt(LocalDateTime.now());
        task.setEscalatedBy(TokenContext.get());
        task.setUpdatedAt(LocalDateTime.now());
        taskRepository.save(task);
        escalateNotify(task, reason);
        if (task.getLeadId() != null) {
            activityService.record(task.getLeadId(), "task_escalated",
                    "任务「" + task.getTitle() + "」已升级", "follow_up_task", task.getId());
        }
        audit.record("sales.task.escalate", "follow_up_task", id,
                Map.of("reason", reason == null ? "" : reason));
        return toView(task, ownerName(task.getOwnerId()));
    }

    @Transactional
    public TaskView updateStatus(UUID id, String status) {
        FollowUpTask task = access.requireTask(id);
        if (!Set.of("pending", "done", "cancelled").contains(status)) {
            throw new BusinessException(400, "非法任务状态");
        }
        if (!"pending".equals(task.getStatus())) {
            throw BusinessException.conflict("TASK_STATE_INVALID", "已结束的任务不能再改");
        }
        task.setStatus(status);
        task.setCompletedAt("done".equals(status) ? LocalDateTime.now() : null);
        task.setUpdatedAt(LocalDateTime.now());
        taskRepository.save(task);
        closeLeadLoop(task, status);
        audit.record("sales.task.status", "follow_up_task", task.getId(), Map.of("to", status));
        return toView(task, ownerName(task.getOwnerId()));
    }

    /**
     * 任务收口回写线索：完成/放弃都写进活动时间线，并重算下次跟进时间。
     * 否则任务做完了线索还停在「待跟进」，看板数字会一直失真。
     */
    private void closeLeadLoop(FollowUpTask task, String status) {
        if (task.getLeadId() == null) {
            return;
        }
        Lead lead = leadRepository.findById(task.getLeadId()).orElse(null);
        if (lead == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        lead.setLastActivityAt(now);
        lead.setNextFollowUpAt(taskRepository.findNextPendingDueAt(lead.getId()));
        lead.setUpdatedAt(now);
        leadRepository.save(lead);
        boolean done = "done".equals(status);
        activityService.record(lead.getId(), done ? "task_completed" : "task_cancelled",
                (done ? "完成跟进：" : "放弃跟进：") + task.getTitle(),
                "follow_up_task", task.getId());
    }

    public Page<UserOptionView> owners(String keyword, int page, int size) {
        UUID companyId = access.requireCompanyId();
        Pageable p = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 50));
        return userRepository.searchForPicker(companyId,
                        List.of(RolePermissions.EMPLOYEE, RolePermissions.COMPANY_ADMIN,
                                RolePermissions.SUPER_ADMIN),
                        trimToNull(keyword), p)
                .map(u -> new UserOptionView(u.getId(), u.getName(), u.getRole()));
    }

    public void escalateNotify(FollowUpTask task, String reason) {
        List<User> admins = escalationAdmins(task);
        if (admins.isEmpty()) {
            log.warn("任务升级没有可通知的管理员 taskId={} companyId={}",
                    task.getId(), task.getCompanyId());
            return;
        }
        String content = escalationContent(task, reason);
        for (User admin : admins) {
            notificationService.notifyUser(admin.getId(), task.getCompanyId(),
                    UserNotification.TYPE_SALES_TASK_ESCALATED, "任务已升级", content,
                    "/sales/tasks", "follow_up_task", task.getId(),
                    "task_escalated:" + task.getId() + ":" + admin.getId());
        }
    }

    /** 调度器专用：立即投递并返回成功条数，0 表示需要回滚升级标志重试（P1-9）。 */
    public int escalateNotifyNow(FollowUpTask task, String reason) {
        List<User> admins = escalationAdmins(task);
        if (admins.isEmpty()) {
            log.warn("自动升级没有可通知的管理员 taskId={} companyId={}",
                    task.getId(), task.getCompanyId());
            return 0;
        }
        String content = escalationContent(task, reason);
        int sent = 0;
        for (User admin : admins) {
            if (notificationService.notifyNow(admin.getId(), task.getCompanyId(),
                    UserNotification.TYPE_SALES_TASK_ESCALATED, "任务已升级", content,
                    "/sales/tasks", "follow_up_task", task.getId(),
                    "task_escalated:" + task.getId() + ":" + admin.getId())) {
                sent++;
            }
        }
        return sent;
    }

    private List<User> escalationAdmins(FollowUpTask task) {
        return userRepository.findByCompanyIdAndRoleInAndIsActiveTrue(
                task.getCompanyId(), List.of(RolePermissions.COMPANY_ADMIN,
                        RolePermissions.SUPER_ADMIN));
    }

    private String escalationContent(FollowUpTask task, String reason) {
        return "任务：" + task.getTitle()
                + (task.getDueAt() == null ? "" : "；截止：" + task.getDueAt())
                + (reason == null || reason.isBlank() ? "" : "；原因：" + reason);
    }

    private TaskView toView(FollowUpTask t, String ownerName) {
        return new TaskView(t.getId(), t.getLeadId(), t.getReviewId(), t.getOwnerId(),
                ownerName, t.getTitle(), t.getChannel(), t.getContent(), t.getDueAt(),
                t.getStatus(), t.getCompletedAt(), t.getEscalatedAt());
    }

    private String ownerName(UUID userId) {
        return userRepository.findById(userId).map(User::getName).orElse(null);
    }

    private Map<UUID, String> loadNames(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> out = new HashMap<>();
        userRepository.findAllById(ids).forEach(u -> out.put(u.getId(), u.getName()));
        return out;
    }

    private String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
