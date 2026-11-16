package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 分配截止延期。
 *
 * <p>规则（docs/56 §4.5）：</p>
 * <ul>
 *   <li>只允许向后延长、只对未来时间；</li>
 *   <li>只允许 not_started/in_progress 的分配；</li>
 *   <li>进行中的考试按 min(开始+时长, 新分配截止) 重算，且只延长不缩短；</li>
 *   <li>已提交/已出分/已逾期的尝试一律不动；</li>
 *   <li>首次延期保留 original_deadline。</li>
 * </ul>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingDeadlineExtensionService {

    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingAttemptRepository attemptRepository;
    private final TrainingTaskRepository taskRepository;
    private final TrainingAccessService accessService;
    private final com.aiextract.config.CompanyScopeService companyScopeService;
    private final TrainingAuditService auditService;
    private final TrainingNotificationService notificationService;
    /** 自代理：批次延期逐人走独立短事务，一个人失败不影响其他人 */
    private final org.springframework.beans.factory.ObjectProvider<TrainingDeadlineExtensionService> selfProvider;

    public record ExtensionResult(TrainingAssignment assignment, LocalDateTime oldDeadline,
                                  LocalDateTime newDeadline, int updatedAttempts) {
    }

    /** 批次延期回执：逐人结果（成功/跳过原因），不只回一个总数 */
    public record CohortExtensionResult(UUID cohortId, LocalDateTime newDeadline,
                                        int extended, int skipped,
                                        List<Map<String, Object>> items) {
    }

    @Transactional(rollbackFor = Exception.class)
    public ExtensionResult extend(UUID assignmentId, LocalDateTime newDeadline, String reason) {
        validateNewDeadline(newDeadline);
        TrainingAssignment snapshot = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        accessService.assertCanReview(snapshot.getCohortId());

        TrainingAssignment locked = assignmentRepository.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        if (!companyScopeService.isUnrestrictedDataScope()
                && Objects.equals(TokenContext.get(), locked.getLearnerId())) {
            throw new BusinessException(403, "不能延长自己的截止时间");
        }
        return applyLocked(locked, newDeadline, reason, TokenContext.get(), false);
    }

    /**
     * 批次级延期：对批次内每个未结束的分配单独延长截止。
     *
     * <p>逐人独立事务（失败只影响该人），返回逐人回执：
     * 已认证/已取消/新截止不晚于个人当前截止的学员会被跳过并给出原因，
     * 不会因为一个人失败就整批回滚。</p>
     */
    public CohortExtensionResult extendCohort(UUID cohortId, LocalDateTime newDeadline, String reason) {
        validateNewDeadline(newDeadline);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, "延期必须填写理由");
        }
        accessService.assertCanReview(cohortId);
        List<TrainingAssignment> assignments =
                assignmentRepository.findByCohortIdOrderByCreatedAtAsc(cohortId);
        List<Map<String, Object>> items = new java.util.ArrayList<>(assignments.size());
        int extended = 0;
        int skipped = 0;
        for (TrainingAssignment assignment : assignments) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("assignmentId", assignment.getId().toString());
            item.put("learnerId", assignment.getLearnerId().toString());
            item.put("status", assignment.getStatus());
            item.put("oldDeadline", assignment.getDeadline());
            if (TrainingAssignment.STATUS_CERTIFIED.equals(assignment.getStatus())
                    || TrainingAssignment.STATUS_CANCELLED.equals(assignment.getStatus())) {
                skipped++;
                item.put("result", "skipped");
                item.put("reason", "该学员培训已结束，无需延期");
                items.add(item);
                continue;
            }
            try {
                ExtensionResult result = selfProvider.getObject()
                        .extend(assignment.getId(), newDeadline, reason);
                extended++;
                item.put("result", "extended");
                item.put("deadline", result.newDeadline());
                item.put("updatedAttempts", result.updatedAttempts());
            } catch (BusinessException e) {
                skipped++;
                item.put("result", "skipped");
                item.put("reason", e.getMessage());
            }
            items.add(item);
        }
        auditService.record("training.deadline.extend_cohort", "training_cohort", cohortId,
                Map.of("newDeadline", newDeadline.toString(), "extended", extended,
                        "skipped", skipped, "reason", reason.trim()));
        log.info("批次截止延期 cohortId={} -> {} extended={} skipped={}",
                cohortId, newDeadline, extended, skipped);
        return new CohortExtensionResult(cohortId, newDeadline, extended, skipped, items);
    }

    /**
     * 在已持有分配行锁的事务内执行延期（补训服务复用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ExtensionResult applyLocked(TrainingAssignment locked, LocalDateTime newDeadline, String reason) {
        return applyLocked(locked, newDeadline, reason, TokenContext.get(), false);
    }

    /**
     * 补训附带的新截止。
     *
     * <p>补训允许落在 {@code pending_certification} 的分配上（只有选修任务失败），
     * 此时主管设置的新截止不能沿用「只允许未开始/进行中」的延期规则，
     * 否则合法补训会被 409 挡住；已认证/已取消仍拒绝。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public ExtensionResult applyForRemediation(TrainingAssignment locked, LocalDateTime newDeadline,
                                               String reason) {
        return applyLocked(locked, newDeadline, reason, TokenContext.get(), true);
    }

    private ExtensionResult applyLocked(TrainingAssignment locked, LocalDateTime newDeadline,
                                        String reason, UUID operator,
                                        boolean allowPendingCertification) {
        validateNewDeadline(newDeadline);
        boolean extendable = TrainingAssignment.STATUS_NOT_STARTED.equals(locked.getStatus())
                || TrainingAssignment.STATUS_IN_PROGRESS.equals(locked.getStatus())
                || (allowPendingCertification
                && TrainingAssignment.STATUS_PENDING_CERTIFICATION.equals(locked.getStatus()));
        if (!extendable) {
            throw BusinessException.conflict("DEADLINE_NOT_EXTENDABLE",
                    "只有未开始/进行中的分配可以延期");
        }
        LocalDateTime oldDeadline = locked.getDeadline();
        if (oldDeadline != null && !newDeadline.isAfter(oldDeadline)) {
            throw BusinessException.conflict("DEADLINE_NOT_FORWARD",
                    "新截止必须晚于当前截止（只能延长）");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, "延期必须填写理由");
        }

        int updated = assignmentRepository.extendDeadline(
                locked.getId(), newDeadline, oldDeadline, operator, reason.trim());
        if (updated == 0) {
            throw BusinessException.conflict("DEADLINE_STATE_CHANGED", "分配状态已变更，请刷新后重试");
        }
        int attempts = extendInProgressAttempts(locked, newDeadline);

        // 同步一级缓存，调用方（补训）后续判断读到新截止
        locked.setDeadline(newDeadline);
        if (locked.getOriginalDeadline() == null) {
            locked.setOriginalDeadline(oldDeadline);
        }
        locked.setDeadlineExtendedAt(LocalDateTime.now());
        locked.setDeadlineExtendedBy(operator);
        locked.setDeadlineExtendReason(reason.trim());

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("oldDeadline", oldDeadline);
        detail.put("newDeadline", newDeadline);
        detail.put("reason", reason.trim());
        detail.put("updatedAttempts", attempts);
        auditService.record("training.deadline.extend", "training_assignment",
                locked.getId(), detail);
        notificationService.notifyLearner(locked,
                com.aiextract.model.UserNotification.TYPE_DEADLINE_EXTENDED, "培训截止时间已延长",
                "新的截止时间：" + newDeadline + "。原因：" + reason.trim(),
                "/training", "training:deadline:" + locked.getId() + ":" + newDeadline);
        log.info("培训截止延期 assignmentId={} {} -> {} attempts={}",
                locked.getId(), oldDeadline, newDeadline, attempts);
        return new ExtensionResult(locked, oldDeadline, newDeadline, attempts);
    }

    private int extendInProgressAttempts(TrainingAssignment assignment, LocalDateTime newDeadline) {
        List<TrainingAttempt> attempts = attemptRepository.findInProgressExamAttempts(assignment.getId());
        int updated = 0;
        for (TrainingAttempt attempt : attempts) {
            if (attempt.getDeadline() == null || attempt.getStartedAt() == null) {
                continue;
            }
            TrainingTask task = taskRepository.findById(attempt.getTaskId()).orElse(null);
            if (task == null || task.getExamDurationMinutes() == null) {
                continue;
            }
            LocalDateTime examEnd = attempt.getStartedAt().plusMinutes(task.getExamDurationMinutes());
            LocalDateTime candidate = examEnd.isBefore(newDeadline) ? examEnd : newDeadline;
            if (!candidate.isAfter(attempt.getDeadline())) {
                continue; // 只延长不缩短
            }
            updated += attemptRepository.extendAttemptDeadline(
                    attempt.getId(), attempt.getDeadline(), candidate);
        }
        return updated;
    }

    private void validateNewDeadline(LocalDateTime newDeadline) {
        if (newDeadline == null) {
            throw new BusinessException(400, "新截止不能为空");
        }
        if (!newDeadline.isAfter(LocalDateTime.now())) {
            throw BusinessException.conflict("DEADLINE_NOT_FORWARD", "新截止必须晚于当前时间");
        }
    }
}
