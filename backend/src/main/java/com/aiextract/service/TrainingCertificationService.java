package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingCertification;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingCertificationRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 认证确认 —— 必修全部通过后，由主管/导师显式确认上岗。
 *
 * <p>不信任前端与冗余计数：认证前从 task_record + 冻结任务表重算必修通过数，
 * 并持有分配行锁，避免并发确认撞 `uq_tcf_assignment`。</p>
 *
 * <p>数据范围与复核同源：assignment → cohort → {@link TrainingAccessService#assertCanReview}；
 * 不能确认自己（D26，super_admin 例外）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingCertificationService {

    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingCertificationRepository certificationRepository;
    private final TrainingTaskRecordRepository recordRepository;
    private final TrainingTaskRepository taskRepository;
    private final com.aiextract.repository.TrainingAttemptRepository attemptRepository;
    private final UserRepository userRepository;
    private final TrainingAccessService accessService;
    private final CompanyScopeService companyScopeService;
    private final TrainingRecordAggregator aggregator;
    private final TrainingAuditService auditService;
    private final TrainingNotificationService notificationService;

    public record RevocationResult(TrainingAssignment assignment, TrainingCertification revoked,
                                   String revokedByName, String learnerName) {
    }

    /** 认证结果：确认后的分配 + 认证记录 + 幂等标识 + 展示姓名 */
    public record CertificationResult(TrainingAssignment assignment,
                                      TrainingCertification certification,
                                      boolean alreadyCertified,
                                      String confirmedByName,
                                      String learnerName) {
    }

    /**
     * 认证确认；已确认时幂等返回既有记录。
     */
    @Transactional(rollbackFor = Exception.class)
    public CertificationResult certify(UUID assignmentId, String note) {
        // 先做无锁读取与数据范围校验，避免对任意已知 UUID 先加行锁（跨租户锁争用）
        TrainingAssignment snapshot = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        TrainingCohort cohort = accessService.assertCanReview(snapshot.getCohortId());

        // 行锁：并发确认时第二个请求会等第一个提交，随后走幂等分支
        TrainingAssignment assignment = assignmentRepository.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));

        if (!companyScopeService.isUnrestrictedDataScope()
                && Objects.equals(TokenContext.get(), assignment.getLearnerId())) {
            throw new BusinessException(403, "不能认证自己的培训");
        }
        // 选修/必修只要还有未裁决尝试就不能认证，否则认证关闭分配后这些尝试永久不可复核
        if (attemptRepository.existsByAssignmentIdAndStatusIn(assignmentId, java.util.List.of(
                com.aiextract.model.TrainingAttempt.STATUS_SUBMITTED,
                com.aiextract.model.TrainingAttempt.STATUS_GRADING,
                com.aiextract.model.TrainingAttempt.STATUS_PENDING_REVIEW,
                com.aiextract.model.TrainingAttempt.STATUS_ERROR))) {
            throw BusinessException.conflict("CERT_OPEN_ATTEMPTS",
                    "还有未裁决的尝试，请先完成复核或等待评分结束");
        }

        TrainingCertification existing = certificationRepository
                .findByAssignmentIdAndStatus(assignmentId, TrainingCertification.STATUS_CONFIRMED)
                .orElse(null);
        if (existing != null && TrainingAssignment.STATUS_CERTIFIED.equals(assignment.getStatus())) {
            return new CertificationResult(assignment, existing, true,
                    userName(existing.getConfirmedBy()), userName(assignment.getLearnerId()));
        }
        if (TrainingAssignment.STATUS_CERTIFIED.equals(assignment.getStatus())) {
            // 已认证但缺少生效认证记录：数据异常，不能重走"必修未过"的错误文案
            throw BusinessException.conflict("CERT_RECORD_MISSING", "认证记录异常，请联系管理员");
        }

        if (TrainingAssignment.STATUS_CANCELLED.equals(assignment.getStatus())) {
            throw new BusinessException(409, "该分配已取消，不能认证");
        }
        if (!TrainingAssignment.STATUS_PENDING_CERTIFICATION.equals(assignment.getStatus())) {
            throw BusinessException.conflict("CERT_REQUIREMENTS_NOT_MET",
                    "必修任务尚未全部通过，不能认证");
        }

        // 从证据重算，不直接读 required_passed 冗余列；补训轮通过同样计入
        List<TrainingTaskRecord> records =
                recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId);
        Set<UUID> passedTaskIds = aggregator.passedTaskIds(records);
        List<TrainingTask> tasks = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(
                        cohort.getPlanId(), cohort.getPlanVersion());
        int requiredTotal = 0;
        int requiredPassed = 0;
        int satisfiedTotal = 0;
        for (TrainingTask task : tasks) {
            boolean satisfied = passedTaskIds.contains(task.getId());
            if (satisfied) {
                satisfiedTotal++;
            }
            if (!Boolean.TRUE.equals(task.getIsRequired())) {
                continue;
            }
            requiredTotal++;
            if (satisfied) {
                requiredPassed++;
            }
        }
        // 全选修计划：全部任务满足才可认证；有必修：必修必须全过
        boolean eligible = requiredTotal > 0
                ? requiredPassed >= requiredTotal
                : !tasks.isEmpty() && satisfiedTotal >= tasks.size();
        if (!eligible) {
            throw BusinessException.conflict("CERT_REQUIREMENTS_NOT_MET",
                    "必修任务尚未全部通过，不能认证");
        }

        LocalDateTime now = LocalDateTime.now();
        // 认证历史追加式：撤销后重新认证插入新行，不复活旧 revoked 行
        TrainingCertification certification = TrainingCertification.builder()
                .id(UUID.randomUUID())
                .companyId(assignment.getCompanyId())
                .assignmentId(assignmentId)
                .learnerId(assignment.getLearnerId())
                .status(TrainingCertification.STATUS_CONFIRMED)
                .confirmedBy(TokenContext.get())
                .confirmedAt(now)
                .note(note == null || note.isBlank() ? null : note.trim())
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            certificationRepository.saveAndFlush(certification);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw BusinessException.conflict("CERT_STATE_CHANGED", "认证状态已变更，请刷新后重试");
        }

        int updated = assignmentRepository.updateStatus(assignmentId,
                TrainingAssignment.STATUS_PENDING_CERTIFICATION,
                TrainingAssignment.STATUS_CERTIFIED);
        if (updated == 0) {
            throw BusinessException.conflict("CERT_STATE_CHANGED", "认证状态已变更，请刷新后重试");
        }
        assignment.setStatus(TrainingAssignment.STATUS_CERTIFIED);
        auditService.record("training.certify", "training_assignment", assignmentId,
                java.util.Map.of("certificationId", certification.getId().toString(),
                        "learnerId", assignment.getLearnerId().toString()));
        notificationService.notifyLearner(assignment,
                com.aiextract.model.UserNotification.TYPE_CERTIFIED, "培训认证已确认",
                "主管已确认你完成全部必修并通过认证。",
                "/training", "training:certified:" + certification.getId());
        log.info("培训认证完成 assignmentId={} learner={} confirmedBy={}",
                assignmentId, assignment.getLearnerId(), TokenContext.get());
        return new CertificationResult(assignment, certification, false,
                userName(certification.getConfirmedBy()), userName(assignment.getLearnerId()));
    }

    /**
     * 撤销认证：认证记录改 revoked（保留原确认信息），分配回到待认证。
     *
     * <p>撤销后是否需要重新完成必修由现有证据决定：若必修仍全过，可直接重新认证；
     * 若已不满足，进度页会给出缺项清单。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public RevocationResult revoke(UUID assignmentId, String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new BusinessException(400, "撤销理由至少 5 个字");
        }
        TrainingAssignment snapshot = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        accessService.assertCanReview(snapshot.getCohortId());

        TrainingAssignment assignment = assignmentRepository.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训数据不存在或不可访问"));
        if (!companyScopeService.isUnrestrictedDataScope()
                && Objects.equals(TokenContext.get(), assignment.getLearnerId())) {
            throw new BusinessException(403, "不能撤销自己的认证");
        }
        if (!TrainingAssignment.STATUS_CERTIFIED.equals(assignment.getStatus())) {
            throw BusinessException.conflict("CERT_NOT_CONFIRMED", "该学员当前不是已认证状态");
        }
        TrainingCertification confirmed = certificationRepository
                .findByAssignmentIdAndStatus(assignmentId, TrainingCertification.STATUS_CONFIRMED)
                .orElseThrow(() -> BusinessException.conflict("CERT_RECORD_MISSING",
                        "认证记录异常，请联系管理员"));

        LocalDateTime now = LocalDateTime.now();
        confirmed.setStatus(TrainingCertification.STATUS_REVOKED);
        confirmed.setRevokedBy(TokenContext.get());
        confirmed.setRevokedAt(now);
        confirmed.setRevokeReason(reason.trim());
        confirmed.setUpdatedAt(now);
        certificationRepository.save(confirmed);

        int updated = assignmentRepository.revokeCertified(assignmentId);
        if (updated == 0) {
            throw BusinessException.conflict("CERT_STATE_CHANGED", "认证状态已变更，请刷新后重试");
        }
        assignment.setStatus(TrainingAssignment.STATUS_PENDING_CERTIFICATION);

        auditService.record("training.certification.revoke", "training_assignment", assignmentId,
                java.util.Map.of("certificationId", confirmed.getId().toString(),
                        "reason", reason.trim()));
        notificationService.notifyLearner(assignment,
                com.aiextract.model.UserNotification.TYPE_CERTIFICATION_REVOKED, "培训认证已撤销",
                "撤销原因：" + reason.trim(), "/training",
                "training:revoked:" + confirmed.getId());
        log.info("培训认证已撤销 assignmentId={} by={} reason={}",
                assignmentId, TokenContext.get(), reason.trim());
        return new RevocationResult(assignment, confirmed,
                userName(TokenContext.get()), userName(assignment.getLearnerId()));
    }

    /** 用户姓名；不存在返回 null，不阻断认证主流程 */
    private String userName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(com.aiextract.model.User::getName).orElse(null);
    }
}
