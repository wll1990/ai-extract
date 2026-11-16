package com.aiextract.service;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 任务进度状态机 —— 所有「任务结论」的唯一写入口。
 *
 * <p>一次结论会同时改动四处，必须在**同一事务**内完成：</p>
 * <ol>
 *   <li>任务进度行状态（带旧状态守卫，避免并发重复推进）；</li>
 *   <li>分配上的冗余计数（只用增量，且只在「非终态 → 终态」时 +1，防重考重复累加）；</li>
 *   <li>解锁依赖它的后继任务（locked → available）；</li>
 *   <li>必修全部通过时把分配推进到 pending_certification。</li>
 * </ol>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingProgressService {

    private static final Set<String> TERMINAL = Set.of(
            TrainingTaskRecord.STATUS_PASSED,
            TrainingTaskRecord.STATUS_FAILED,
            TrainingTaskRecord.STATUS_WAIVED);

    private final TrainingTaskRecordRepository recordRepository;
    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingCohortRepository cohortRepository;
    private final TrainingTaskRepository taskRepository;
    private final TrainingRecordAggregator aggregator;
    private final TrainingNotificationService notificationService;
    private final ObjectMapper objectMapper;

    /** 阅读任务「标记已读」：置 passed + 记录确认时间，并按状态迁移累加计数 */
    @Transactional
    public void acknowledgeReading(UUID recordId) {
        TrainingTaskRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(404, "培训任务不存在"));
        if (TrainingTaskRecord.STATUS_PASSED.equals(record.getStatus())) {
            return; // 幂等
        }
        String from = record.getStatus();
        LocalDateTime now = LocalDateTime.now();
        int updated = recordRepository.acknowledge(recordId, TrainingTaskRecord.STATUS_PASSED, now);
        if (updated == 0) {
            throw new BusinessException(409, "当前状态不能标记已读，请刷新后重试");
        }
        // @Modifying 不会刷新一级缓存；同步内存状态，否则后续解锁读到的仍是旧值
        record.setStatus(TrainingTaskRecord.STATUS_PASSED);
        record.setAcknowledgedAt(now);
        applyCounters(record.getAssignmentId(), record.getTaskId(), from, true);
    }

    /**
     * 陪练/考试出结论（评分或复核调用）。
     *
     * @param passed 是否通过
     */
    @Transactional
    public void applyRecordResult(UUID recordId, boolean passed) {
        TrainingTaskRecord record = recordRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(404, "培训任务不存在"));
        String from = record.getStatus();
        // 已通过/已豁免是终态；failed 不是——补考通过必须能从 failed 迁到 passed，
        // 否则「首次没过、补考过了」的学员永远拿不到通过
        if (TrainingTaskRecord.STATUS_PASSED.equals(from)
                || TrainingTaskRecord.STATUS_WAIVED.equals(from)) {
            return;
        }
        String to = passed ? TrainingTaskRecord.STATUS_PASSED : TrainingTaskRecord.STATUS_FAILED;
        LocalDateTime now = LocalDateTime.now();
        int updated = recordRepository.updateStatus(recordId, from, to, now);
        if (updated == 0) {
            return; // 并发已被其他请求推进
        }
        // 同步一级缓存，保证解锁逻辑读到最新状态
        record.setStatus(to);
        record.setFinishedAt(now);
        if (record.getRoundNo() != null && record.getRoundNo() > 1) {
            // 补训轮：同一任务已有历史终态，delta 会重复计数，改为锁分配后绝对重算
            assignmentRepository.updateStatus(record.getAssignmentId(),
                    TrainingAssignment.STATUS_NOT_STARTED, TrainingAssignment.STATUS_IN_PROGRESS);
            recomputeCounters(record.getAssignmentId());
            if (passed) {
                afterTaskPassed(record.getAssignmentId());
            } else {
                // 补训轮失败同样是终态：必须重新判定是否还能回到待认证。
                // 否则「待认证 → 补训 → 补训失败」会把分配永久卡在 in_progress，
                // 而认证接口只接受 pending_certification。
                reevaluateCertificationState(record.getAssignmentId());
            }
        } else {
            applyCounters(record.getAssignmentId(), record.getTaskId(), from, passed);
            if (!passed) {
                reevaluateCertificationState(record.getAssignmentId());
            }
        }
    }

    /**
     * 绝对重算冗余计数（补训轮专用）。
     *
     * <p>口径见 {@link TrainingRecordAggregator}：completed=任一终态任务数，
     * passed=任一通过任务数，requiredPassed=任一通过/豁免的必修任务数。</p>
     */
    @Transactional
    public void recomputeCounters(UUID assignmentId) {
        TrainingAssignment assignment = assignmentRepository.findByIdForUpdate(assignmentId)
                .orElseThrow(() -> new BusinessException(404, "培训分配不存在"));
        TrainingCohort cohort = cohortRepository.findById(assignment.getCohortId()).orElse(null);
        if (cohort == null) {
            return;
        }
        List<TrainingTask> tasks = taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(
                cohort.getPlanId(), cohort.getPlanVersion());
        List<TrainingTaskRecord> records =
                recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId);
        TrainingRecordAggregator.Counters counters = aggregator.count(records, tasks);
        assignmentRepository.setCounters(assignmentId,
                counters.completed(), counters.passed(), counters.requiredPassed());
    }

    /**
     * 评分需人工复核：进度行置 pending_review。
     *
     * <p>此时**不累加完成/通过计数**——结论还没出来，计数必须等复核定稿后由
     * {@link #applyRecordResult} 一次性写入，否则退回时会留下无法回滚的假完成。</p>
     */
    @Transactional
    public void markPendingReview(UUID recordId) {
        int updated = recordRepository.markPendingReview(recordId);
        if (updated == 0) {
            log.debug("进度行 {} 已不在可复核状态，跳过置 pending_review", recordId);
        }
    }

    /** 开始任务：分配 not_started → in_progress（已有进展时不动） */
    @Transactional
    public void markAssignmentStarted(UUID assignmentId) {
        assignmentRepository.updateStatus(assignmentId,
                TrainingAssignment.STATUS_NOT_STARTED, TrainingAssignment.STATUS_IN_PROGRESS);
    }

    // ==================== 内部 ====================

    private void applyCounters(UUID assignmentId, UUID taskId, String fromStatus, boolean passed) {
        // 有任何进展就说明学员已经开始，不依赖前端是否点过「开始」
        assignmentRepository.updateStatus(assignmentId,
                TrainingAssignment.STATUS_NOT_STARTED, TrainingAssignment.STATUS_IN_PROGRESS);

        boolean wasTerminal = TERMINAL.contains(fromStatus);
        int completedDelta = wasTerminal ? 0 : 1;
        // 通过增量与「是否首次出结论」无关：failed→passed 的补考必须补记通过，
        // 否则必修任务首次没过、补考过了，requiredPassed 永远不够，认证链路断
        int passedDelta = (passed && !TrainingTaskRecord.STATUS_PASSED.equals(fromStatus)) ? 1 : 0;

        TrainingTask task = taskRepository.findById(taskId).orElse(null);
        boolean required = task != null && Boolean.TRUE.equals(task.getIsRequired());
        int requiredDelta = (passedDelta == 1 && required) ? 1 : 0;

        int counterRows = assignmentRepository.addCounters(assignmentId, completedDelta, passedDelta, requiredDelta);
        log.info("training.counters assignment={} from={} passed={} delta={}/{}/{} rows={}",
                assignmentId, fromStatus, passed, completedDelta, passedDelta, requiredDelta, counterRows);

        if (passed) {
            afterTaskPassed(assignmentId);
        }
    }

    /**
     * 任务通过后的连锁动作：解锁后继 + 判定是否该进待认证。
     *
     * <p>必修是否全过**从 task_record + 冻结任务表现算**，不读 assignment 上的冗余计数：
     * addCounters 是 @Modifying，不会刷新一级缓存，此时内存里的 requiredPassed 还是旧值，
     * 直接读会静默漏掉最后一次推进。</p>
     */
    private void afterTaskPassed(UUID assignmentId) {
        List<TrainingTaskRecord> records = recordRepository
                .findByAssignmentIdOrderByCreatedAtAsc(assignmentId);
        // 豁免视同满足；补训轮通过同样计入（聚合器统一口径）
        Set<UUID> passedTaskIds = aggregator.passedTaskIds(records);
        TrainingAssignment assignment = assignmentRepository.findById(assignmentId).orElse(null);
        if (assignment == null) {
            return;
        }
        TrainingCohort cohort = cohortRepository.findById(assignment.getCohortId()).orElse(null);
        if (cohort == null) {
            return;
        }
        List<TrainingTask> tasks = taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(
                cohort.getPlanId(), cohort.getPlanVersion());
        Map<UUID, List<UUID>> prerequisites = new HashMap<>();
        for (TrainingTask task : tasks) {
            prerequisites.put(task.getId(), parsePrerequisites(task));
        }

        for (TrainingTaskRecord r : records) {
            if (!TrainingTaskRecord.STATUS_LOCKED.equals(r.getStatus())) {
                continue;
            }
            List<UUID> prereqs = prerequisites.getOrDefault(r.getTaskId(), List.of());
            // 空列表（无前置或前置 JSON 损坏）视为无依赖：宁可解锁也不要让任务永久锁死
            if (passedTaskIds.containsAll(prereqs)) {
                recordRepository.unlock(r.getId());
            }
        }

        reevaluate(assignment, records, tasks);
    }

    /**
     * 重新判定分配应处于 in_progress 还是 pending_certification。
     *
     * <p>判定条件：必修（无必修则全部任务）按「任一通过/豁免」口径满足，
     * <b>且没有未出结论的补训轮</b>。任何轮次出终态（含补训失败）后都要重算：
     * 否则「待认证 → 补训 → 补训失败」会让分配永久停在 in_progress，
     * 而 {@code certify} 只接受 pending_certification，等于永久不能认证。</p>
     */
    @Transactional
    public void reevaluateCertificationState(UUID assignmentId) {
        TrainingAssignment assignment = assignmentRepository.findById(assignmentId).orElse(null);
        if (assignment == null || !assignment.isOpen()) {
            return;
        }
        TrainingCohort cohort = cohortRepository.findById(assignment.getCohortId()).orElse(null);
        if (cohort == null) {
            return;
        }
        List<TrainingTaskRecord> records =
                recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId);
        List<TrainingTask> tasks = taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(
                cohort.getPlanId(), cohort.getPlanVersion());
        reevaluate(assignment, records, tasks);
    }

    private void reevaluate(TrainingAssignment assignment, List<TrainingTaskRecord> records,
                            List<TrainingTask> tasks) {
        if (!assignment.isOpen()) {
            return;
        }
        Set<UUID> satisfied = aggregator.passedTaskIds(records);
        int requiredTotal = 0;
        int requiredPassed = 0;
        int satisfiedTotal = 0;
        for (TrainingTask task : tasks) {
            boolean ok = satisfied.contains(task.getId());
            if (ok) {
                satisfiedTotal++;
            }
            if (!Boolean.TRUE.equals(task.getIsRequired())) {
                continue;
            }
            requiredTotal++;
            if (ok) {
                requiredPassed++;
            }
        }
        // 没有必修任务的计划：全部任务都满足后才进入待认证，避免永远卡在 in_progress
        boolean ready = requiredTotal > 0
                ? requiredPassed >= requiredTotal
                : !tasks.isEmpty() && satisfiedTotal >= tasks.size();
        boolean blockedByRemediation = aggregator.hasUnfinishedRemediation(records);
        if (ready && !blockedByRemediation) {
            // 守卫更新可能 0 行（并发下已被认证/取消）；只有真的推进成功才同步内存状态，
            // 否则提交时的脏检查会把并发方的状态覆盖回去
            int updated = assignmentRepository.markPendingCertification(
                    assignment.getId(), TrainingAssignment.STATUS_PENDING_CERTIFICATION);
            if (updated > 0) {
                assignment.setStatus(TrainingAssignment.STATUS_PENDING_CERTIFICATION);
                notifyPendingCertification(assignment);
            }
        } else if (TrainingAssignment.STATUS_PENDING_CERTIFICATION.equals(assignment.getStatus())) {
            // 条件回退：已有未完成的补训轮，不能停在待认证（会有「待认证却不能认证」的错觉）
            int updated = assignmentRepository.updateStatus(assignment.getId(),
                    TrainingAssignment.STATUS_PENDING_CERTIFICATION,
                    TrainingAssignment.STATUS_IN_PROGRESS);
            if (updated > 0) {
                assignment.setStatus(TrainingAssignment.STATUS_IN_PROGRESS);
            }
        }
    }

    /**
     * 进入待认证时双通知：主管需要知道「有人可以认证了」，学员需要知道「不是自己没做完」。
     *
     * <p>按「分配 + 日期」去重：撤销认证后重新进入待认证会再次提醒，但同一天不会重复刷。</p>
     */
    private void notifyPendingCertification(TrainingAssignment assignment) {
        String dedupe = "training:pending-cert:" + assignment.getId() + ":" + LocalDate.now();
        notificationService.notifyReviewerByCohortId(assignment.getCohortId(),
                com.aiextract.model.UserNotification.TYPE_PENDING_CERTIFICATION,
                "有学员已完成必修，待认证",
                "学员已完成全部必修任务，请在批次详情确认认证。",
                "/admin/training/cohorts/" + assignment.getCohortId(), dedupe);
        notificationService.notifyLearner(assignment,
                com.aiextract.model.UserNotification.TYPE_PENDING_CERTIFICATION,
                "必修已完成，等待主管认证",
                "你的必修任务已全部通过，主管确认后即可完成本次培训认证。",
                "/training", dedupe);
    }

    private List<UUID> parsePrerequisites(TrainingTask task) {
        String json = task.getPrerequisites();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> raw = objectMapper.readValue(json, new TypeReference<List<String>>() { });
            List<UUID> ids = new ArrayList<>(raw.size());
            for (String s : raw) {
                if (s != null && !s.isBlank()) {
                    ids.add(UUID.fromString(s));
                }
            }
            return ids;
        } catch (Exception e) {
            log.warn("任务 {} 的前置 JSON 无法解析：{}", task.getId(), json);
            return List.of();
        }
    }

    /** 供上层判断任务是否已终结（避免重复开始） */
    public boolean isTerminal(String status) {
        return status != null && TERMINAL.contains(status);
    }

    /** 供上层构造阻断原因 */
    public boolean isPassedStatus(String status) {
        return Objects.equals(TrainingTaskRecord.STATUS_PASSED, status);
    }
}
