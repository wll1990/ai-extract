package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 补训/整改 —— 在原分配下为失败任务追加一轮记录。
 *
 * <p>为什么不是「同任务再建一行」：原唯一键是 (assignment_id, task_id)，
 * P3 改为 (assignment_id, task_id, round_no)，原失败行保留，补训行 round+1。
 * 学员端与看板只看最新一轮；计数与认证按「任一通过」口径重算。</p>
 *
 * <p>规则：只允许最新一轮且 failed 的实践/考试任务；阅读任务没有能力结论；
 * 已认证的分配必须先撤销认证；分配已过期时必须同时给新截止。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingRemediationService {

    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingTaskRecordRepository recordRepository;
    private final TrainingTaskRepository taskRepository;
    private final com.aiextract.repository.TrainingAttemptRepository attemptRepository;
    private final TrainingAccessService accessService;
    private final TrainingDeadlineExtensionService deadlineExtensionService;
    private final TrainingProgressService progressService;
    private final TrainingAuditService auditService;
    private final TrainingNotificationService notificationService;

    @Value("${app.training.remediation.max-rounds:10}")
    private int maxRounds;

    public record RemediationResult(TrainingTaskRecord record, TrainingAssignment assignment, int roundNo) {
    }

    /**
     * 免修/豁免：管理员确认某任务不需要完成。
     * 计入认证满足，但不计入 passed 计数；已认证需先撤销。
     */
    @Transactional(rollbackFor = Exception.class)
    public TrainingTaskRecord waive(UUID assignmentId, UUID recordId, String reason) {
        if (reason == null || reason.trim().length() < 2) {
            throw new BusinessException(400, "免修原因至少 2 个字");
        }
        TrainingAssignment snapshot = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        accessService.assertCanReview(snapshot.getCohortId());
        TrainingAssignment assignment = assignmentRepository.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        if (Objects.equals(TokenContext.get(), assignment.getLearnerId())) {
            throw new BusinessException(403, "不能给自己免修");
        }
        if (TrainingAssignment.STATUS_CANCELLED.equals(assignment.getStatus())) {
            throw BusinessException.conflict("WAIVE_ASSIGNMENT_CLOSED", "分配已取消，不能免修");
        }
        if (TrainingAssignment.STATUS_CERTIFIED.equals(assignment.getStatus())) {
            throw BusinessException.conflict("WAIVE_ASSIGNMENT_CLOSED", "已认证，请先撤销认证");
        }
        TrainingTaskRecord target = recordRepository.findByIdAndAssignmentId(recordId, assignmentId)
                .orElseThrow(() -> BusinessException.conflict("WAIVE_TARGET_INVALID", "任务不属于该分配"));
        int latestRound = recordRepository.findMaxRoundNo(assignmentId, target.getTaskId());
        if (target.getRoundNo() == null || target.getRoundNo() != latestRound) {
            throw BusinessException.conflict("WAIVE_NOT_LATEST", "只能对最新一轮免修");
        }
        if (TrainingTaskRecord.STATUS_WAIVED.equals(target.getStatus())) {
            return target;
        }
        if (attemptRepository.existsByTaskRecordIdAndStatusIn(target.getId(), java.util.List.of(
                com.aiextract.model.TrainingAttempt.STATUS_IN_PROGRESS,
                com.aiextract.model.TrainingAttempt.STATUS_SUBMITTED,
                com.aiextract.model.TrainingAttempt.STATUS_GRADING,
                com.aiextract.model.TrainingAttempt.STATUS_PENDING_REVIEW,
                com.aiextract.model.TrainingAttempt.STATUS_ERROR))) {
            throw BusinessException.conflict("WAIVE_ATTEMPT_IN_FLIGHT",
                    "该任务还有未裁决的尝试，请先完成复核");
        }
        target.setStatus(TrainingTaskRecord.STATUS_WAIVED);
        target.setWaivedBy(TokenContext.get());
        target.setWaivedReason(reason.trim());
        target.setFinishedAt(LocalDateTime.now());
        target.setUpdatedAt(LocalDateTime.now());
        recordRepository.save(target);
        progressService.recomputeCounters(assignmentId);
        progressService.reevaluateCertificationState(assignmentId);
        auditService.record("training.waive", "training_task_record", recordId,
                java.util.Map.of("assignmentId", assignmentId.toString(), "reason", reason.trim()));
        return target;
    }

    @Transactional(rollbackFor = Exception.class)
    public RemediationResult create(UUID assignmentId, UUID recordId, String reason,
                                    LocalDateTime deadline) {
        if (reason == null || reason.trim().length() < 2) {
            throw new BusinessException(400, "补训原因至少 2 个字");
        }
        TrainingAssignment snapshot = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        accessService.assertCanReview(snapshot.getCohortId());

        TrainingAssignment assignment = assignmentRepository.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));

        if (Objects.equals(TokenContext.get(), assignment.getLearnerId())) {
            throw new BusinessException(403, "不能给自己布置补训");
        }
        if (TrainingAssignment.STATUS_CANCELLED.equals(assignment.getStatus())) {
            throw BusinessException.conflict("REMEDIATION_ASSIGNMENT_CLOSED", "分配已取消，不能补训");
        }
        if (TrainingAssignment.STATUS_CERTIFIED.equals(assignment.getStatus())) {
            throw BusinessException.conflict("REMEDIATION_ASSIGNMENT_CLOSED",
                    "该学员已认证，请先撤销认证再安排补训");
        }

        TrainingTaskRecord target = recordRepository.findByIdAndAssignmentId(recordId, assignmentId)
                .orElseThrow(() -> BusinessException.conflict("REMEDIATION_TARGET_INVALID",
                        "补训目标不属于该分配"));
        int latestRound = recordRepository.findMaxRoundNo(assignmentId, target.getTaskId());
        if (target.getRoundNo() == null || target.getRoundNo() != latestRound) {
            throw BusinessException.conflict("REMEDIATION_NOT_LATEST", "只能对最新一轮安排补训");
        }
        if (!TrainingTaskRecord.STATUS_FAILED.equals(target.getStatus())) {
            throw BusinessException.conflict("REMEDIATION_TARGET_INVALID", "只有未通过的任务可以补训");
        }
        // 同一轮还有在途/待复核尝试时不能补训，否则旧轮永远无法复核
        if (attemptRepository.existsByTaskRecordIdAndStatusIn(target.getId(), java.util.List.of(
                com.aiextract.model.TrainingAttempt.STATUS_IN_PROGRESS,
                com.aiextract.model.TrainingAttempt.STATUS_SUBMITTED,
                com.aiextract.model.TrainingAttempt.STATUS_GRADING,
                com.aiextract.model.TrainingAttempt.STATUS_PENDING_REVIEW,
                com.aiextract.model.TrainingAttempt.STATUS_ERROR))) {
            throw BusinessException.conflict("REMEDIATION_ATTEMPT_IN_FLIGHT",
                    "该轮还有未裁决的尝试，请先完成复核或等待评分结束");
        }

        TrainingTask task = taskRepository.findById(target.getTaskId())
                .orElseThrow(() -> new BusinessException(404, "培训任务不存在"));
        if (task.isReading()) {
            throw BusinessException.conflict("REMEDIATION_TARGET_INVALID", "阅读任务没有能力结论，不需要补训");
        }
        int nextRound = latestRound + 1;
        if (nextRound > maxRounds) {
            throw BusinessException.conflict("REMEDIATION_LIMIT",
                    "补训轮次已达上限（" + maxRounds + "），请联系管理员");
        }

        boolean overdue = assignment.getDeadline() != null
                && assignment.getDeadline().isBefore(LocalDateTime.now());
        if (overdue) {
            if (deadline == null) {
                throw BusinessException.conflict("REMEDIATION_DEADLINE_REQUIRED",
                        "分配已过截止时间，补训必须同时设置新截止");
            }
            deadlineExtensionService.applyForRemediation(assignment, deadline,
                    "补训延期：" + reason.trim());
        } else if (deadline != null) {
            deadlineExtensionService.applyForRemediation(assignment, deadline,
                    "补训延期：" + reason.trim());
        }

        LocalDateTime now = LocalDateTime.now();
        TrainingTaskRecord record = TrainingTaskRecord.builder()
                .id(UUID.randomUUID())
                .assignmentId(assignmentId)
                .taskId(target.getTaskId())
                .roundNo(nextRound)
                .remediationOfRecordId(target.getId())
                .remediationReason(reason.trim())
                .remediationCreatedBy(TokenContext.get())
                .status(TrainingTaskRecord.STATUS_AVAILABLE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            // 唯一键 (assignment_id, task_id, round_no) 兜底：并发补训时第二个请求转 409 而不是 500
            recordRepository.saveAndFlush(record);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw BusinessException.conflict("REMEDIATION_STATE_CHANGED",
                    "补训状态已变更，请刷新后重试");
        }

        // 待认证状态被未完成的补训打断 → 回到进行中；补训出结论后由进度服务重新判定
        progressService.reevaluateCertificationState(assignmentId);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskId", target.getTaskId());
        detail.put("fromRecordId", target.getId());
        detail.put("newRecordId", record.getId());
        detail.put("roundNo", nextRound);
        detail.put("reason", reason.trim());
        detail.put("deadline", deadline);
        auditService.record("training.remediation.create", "training_assignment",
                assignmentId, detail);
        notificationService.notifyLearner(assignment,
                com.aiextract.model.UserNotification.TYPE_REMEDIATION, "主管安排了补训",
                "任务「" + task.getTitle() + "」需要补训，原因：" + reason.trim()
                        + "。请重新完成该任务。",
                "/training", "training:remediation:" + record.getId());

        log.info("补训已创建 assignmentId={} taskId={} round={} by={}",
                assignmentId, target.getTaskId(), nextRound, TokenContext.get());
        return new RemediationResult(record, assignment, nextRound);
    }

    /**
     * 重做（reopen）：主管判定 AI 误判通过，要求学员重新完成。
     *
     * <p>不重置旧记录，而是追加 round+1 / round_type=reopen 的新轮：
     * 旧通过仍是历史证据，但聚合口径「最新轮决定」会让认证门槛立即回到未满足，
     * 避免「已认证」把误判结论固化下来。已认证的分配必须先撤销认证。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RemediationResult reopen(UUID assignmentId, UUID recordId, String reason,
                                    LocalDateTime deadline) {
        if (reason == null || reason.trim().length() < 5) {
            throw new BusinessException(400, "重做原因至少 5 个字（需说明误判依据）");
        }
        TrainingAssignment snapshot = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        accessService.assertCanReview(snapshot.getCohortId());

        TrainingAssignment assignment = assignmentRepository.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        if (Objects.equals(TokenContext.get(), assignment.getLearnerId())) {
            throw new BusinessException(403, "不能给自己布置重做");
        }
        if (TrainingAssignment.STATUS_CANCELLED.equals(assignment.getStatus())) {
            throw BusinessException.conflict("REOPEN_ASSIGNMENT_CLOSED", "分配已取消，不能重做");
        }
        if (TrainingAssignment.STATUS_CERTIFIED.equals(assignment.getStatus())) {
            throw BusinessException.conflict("REOPEN_ASSIGNMENT_CLOSED",
                    "该学员已认证，请先撤销认证再要求重做");
        }

        TrainingTaskRecord target = recordRepository.findByIdAndAssignmentId(recordId, assignmentId)
                .orElseThrow(() -> BusinessException.conflict("REOPEN_TARGET_INVALID",
                        "重做目标不属于该分配"));
        int latestRound = recordRepository.findMaxRoundNo(assignmentId, target.getTaskId());
        if (target.getRoundNo() == null || target.getRoundNo() != latestRound) {
            throw BusinessException.conflict("REOPEN_NOT_LATEST", "只能对最新一轮要求重做");
        }
        if (!TrainingTaskRecord.STATUS_PASSED.equals(target.getStatus())) {
            throw BusinessException.conflict("REOPEN_TARGET_INVALID", "只有已通过的任务可以要求重做");
        }
        if (attemptRepository.existsByTaskRecordIdAndStatusIn(target.getId(), java.util.List.of(
                com.aiextract.model.TrainingAttempt.STATUS_IN_PROGRESS,
                com.aiextract.model.TrainingAttempt.STATUS_SUBMITTED,
                com.aiextract.model.TrainingAttempt.STATUS_GRADING,
                com.aiextract.model.TrainingAttempt.STATUS_PENDING_REVIEW,
                com.aiextract.model.TrainingAttempt.STATUS_ERROR))) {
            throw BusinessException.conflict("REOPEN_ATTEMPT_IN_FLIGHT",
                    "该轮还有未裁决的尝试，请先完成复核或等待评分结束");
        }

        TrainingTask task = taskRepository.findById(target.getTaskId())
                .orElseThrow(() -> new BusinessException(404, "培训任务不存在"));
        if (task.isReading()) {
            throw BusinessException.conflict("REOPEN_TARGET_INVALID",
                    "阅读任务没有 AI 判定，不需要重做；如需重新确认请走免修或重新分配");
        }
        int nextRound = latestRound + 1;
        if (nextRound > maxRounds) {
            throw BusinessException.conflict("REOPEN_LIMIT",
                    "重做轮次已达上限（" + maxRounds + "），请联系管理员");
        }

        boolean overdue = assignment.getDeadline() != null
                && assignment.getDeadline().isBefore(LocalDateTime.now());
        if (overdue) {
            if (deadline == null) {
                throw BusinessException.conflict("REOPEN_DEADLINE_REQUIRED",
                        "分配已过截止时间，重做必须同时设置新截止");
            }
            deadlineExtensionService.applyForRemediation(assignment, deadline,
                    "误判重做延期：" + reason.trim());
        } else if (deadline != null) {
            deadlineExtensionService.applyForRemediation(assignment, deadline,
                    "误判重做延期：" + reason.trim());
        }

        LocalDateTime now = LocalDateTime.now();
        TrainingTaskRecord record = TrainingTaskRecord.builder()
                .id(UUID.randomUUID())
                .assignmentId(assignmentId)
                .taskId(target.getTaskId())
                .roundNo(nextRound)
                .roundType("reopen")
                .remediationOfRecordId(target.getId())
                .remediationReason(reason.trim())
                .remediationCreatedBy(TokenContext.get())
                .status(TrainingTaskRecord.STATUS_AVAILABLE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            recordRepository.saveAndFlush(record);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw BusinessException.conflict("REOPEN_STATE_CHANGED",
                    "重做状态已变更，请刷新后重试");
        }

        progressService.recomputeCounters(assignmentId);
        progressService.reevaluateCertificationState(assignmentId);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskId", target.getTaskId());
        detail.put("fromRecordId", target.getId());
        detail.put("newRecordId", record.getId());
        detail.put("roundNo", nextRound);
        detail.put("reason", reason.trim());
        detail.put("deadline", deadline);
        auditService.record("training.reopen", "training_assignment", assignmentId, detail);
        notificationService.notifyLearner(assignment,
                com.aiextract.model.UserNotification.TYPE_REMEDIATION, "主管要求重做任务",
                "任务「" + task.getTitle() + "」此前判为通过，经复核需要重做，原因："
                        + reason.trim() + "。请重新完成该任务。",
                "/training", "training:reopen:" + record.getId());

        log.info("误判重做已创建 assignmentId={} taskId={} round={} by={}",
                assignmentId, target.getTaskId(), nextRound, TokenContext.get());
        return new RemediationResult(record, assignment, nextRound);
    }
}
