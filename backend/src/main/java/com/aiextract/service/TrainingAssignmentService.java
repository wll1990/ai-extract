package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.CertificationView;
import com.aiextract.dto.TrainingBatchAssignRequest;
import com.aiextract.dto.TrainingCohortRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingCertification;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingPlan;
import com.aiextract.model.TrainingPlanVersion;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.model.User;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingCertificationRepository;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.TrainingPlanRepository;
import com.aiextract.repository.TrainingPlanVersionRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 培训批次与分配服务。
 *
 * <p>核心不变式：</p>
 * <ol>
 *   <li>批次绑定**已发布**计划版本；分配时按该版本的冻结任务集一次性建齐
 *       {@code training_task_record}（一学员一任务一行），后续改计划不影响已分配学员；</li>
 *   <li>初始状态由前置决定：无前置 → {@code available}，有前置 → {@code locked}；</li>
 *   <li>批量分配逐人返回结果：跨企业/不存在/已停用/重复 各自给出原因，不只回一个总数；</li>
 *   <li>取消分配保留历史（任务进度行不删除），只是分配不再推进。</li>
 * </ol>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingAssignmentService {

    private final TrainingCohortRepository cohortRepository;
    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingAttemptRepository attemptRepository;
    private final TrainingTaskRecordRepository taskRecordRepository;
    private final TrainingTaskRepository taskRepository;
    private final TrainingCertificationRepository certificationRepository;
    private final TrainingPlanRepository planRepository;
    private final TrainingPlanVersionRepository versionRepository;
    private final UserRepository userRepository;
    private final TrainingAccessService accessService;
    private final CompanyScopeService companyScopeService;
    private final TrainingNotificationService notificationService;
    private final TrainingAuditService auditService;
    private final TrainingRecordAggregator aggregator;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Value("${app.training.pending-review.threshold-hours:24}")
    private long pendingReviewThresholdHours;

    /** 批次详情：批次 + 计划 + 学员分配 + 学员信息 + 认证视图 */
    public record CohortDetail(TrainingCohort cohort, TrainingPlan plan,
                               List<TrainingAssignment> assignments, Map<UUID, User> learners,
                               Map<UUID, com.aiextract.dto.CertificationView> certifications) {
    }

    /** 逐人分配结果：assigned / skipped / failed */
    public record AssignItem(UUID learnerId, String name, String status, String reason) {
    }

    public record BatchResult(UUID cohortId, int assigned, int skipped, int failed,
                              List<AssignItem> results) {
    }

    /** 必修任务阻塞项（认证前告诉主管缺什么） */
    public record CertificationBlocker(UUID taskId, String title, String recordStatus) {
    }

    /** 单个学员在批次内的进度 */
    public record LearnerProgress(UUID assignmentId, UUID learnerId, String name, String status,
                                  int totalTasks, int requiredTotal, int completedTasks,
                                  int requiredPassed, int passedTasks,
                                  LocalDateTime deadline, boolean overdue,
                                  boolean pendingReview, boolean pendingReviewOverdue,
                                  Long pendingReviewHours, boolean canCertify,
                                  String certifyBlockedReason,
                                  List<CertificationBlocker> certificationBlockers) {
    }

    /** 批次进度：逐学员（当前页）+ 全局汇总 + 认证视图 + 分页元数据 */
    public record CohortProgress(TrainingCohort cohort, List<LearnerProgress> learners,
                                 Map<String, Object> summary,
                                 Map<UUID, com.aiextract.dto.CertificationView> certifications,
                                 int page, int size, long total, int totalPages) {
    }

    /** 我的培训：分页分配 + 批次/计划名称 + 认证视图 */
    public record MyAssignments(Page<TrainingAssignment> page,
                                Map<UUID, TrainingCohort> cohorts,
                                Map<UUID, String> planNames,
                                Map<UUID, com.aiextract.dto.CertificationView> certifications) {
    }

    // ==================== 批次 ====================

    /** 建批次：只能绑定已发布版本；退役计划禁止新建 */
    @Transactional
    public TrainingCohort createCohort(TrainingCohortRequest req) {
        UUID companyId = accessService.requireCompanyId();
        TrainingPlan plan = planRepository.findByIdAndCompanyId(req.getPlanId(), companyId)
                .orElseThrow(() -> new BusinessException(404, "培训计划不存在或不可访问"));
        if (TrainingPlan.STATUS_RETIRED.equals(plan.getStatus())) {
            throw new BusinessException(409, "计划已退役，不能新建批次");
        }
        TrainingPlanVersion version = versionRepository
                .findByPlanIdAndVersion(req.getPlanId(), req.getPlanVersion())
                .orElseThrow(() -> new BusinessException(400, "计划版本不存在"));
        if (!version.isPublished()) {
            throw new BusinessException(409, "只能对已发布的计划版本建批次");
        }
        if (req.getMentorId() != null) {
            User mentor = userRepository.findById(req.getMentorId())
                    .orElseThrow(() -> new BusinessException(400, "导师不存在"));
            if (!companyId.equals(mentor.getCompanyId())) {
                throw new BusinessException(400, "导师不属于本企业");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        TrainingCohort cohort = TrainingCohort.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .planId(req.getPlanId())
                .planVersion(req.getPlanVersion())
                .name(req.getName().trim())
                .mentorId(req.getMentorId())
                .deadline(req.getDeadline())
                .status(TrainingCohort.STATUS_ACTIVE)
                .createdBy(TokenContext.get())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return cohortRepository.save(cohort);
    }

    /** 批次状态流转：active→completed/cancelled/archived；终态只能归档 */
    @Transactional
    public TrainingCohort updateCohortStatus(UUID cohortId, String status) {
        UUID companyId = accessService.requireCompanyId();
        TrainingCohort cohort = cohortRepository.findById(cohortId)
                .filter(c -> companyId.equals(c.getCompanyId()))
                .orElseThrow(() -> new BusinessException(404, "批次不存在或不可访问"));
        if (!TrainingCohort.isValidStatus(status)) {
            throw new BusinessException(400, "未知的批次状态：" + status);
        }
        String current = cohort.getStatus();
        java.util.Set<String> allowed = switch (current) {
            case TrainingCohort.STATUS_ACTIVE ->
                    java.util.Set.of(TrainingCohort.STATUS_COMPLETED, TrainingCohort.STATUS_CANCELLED,
                            TrainingCohort.STATUS_ARCHIVED);
            case TrainingCohort.STATUS_COMPLETED, TrainingCohort.STATUS_CANCELLED ->
                    java.util.Set.of(TrainingCohort.STATUS_ARCHIVED);
            default -> java.util.Set.of();
        };
        if (!allowed.contains(status)) {
            throw new BusinessException(409, "批次状态不能从 " + current + " 变更为 " + status);
        }
        cohort.setStatus(status);
        cohort.setUpdatedAt(LocalDateTime.now());
        return cohortRepository.save(cohort);
    }

    /** 批次列表（企业内，按计划/状态过滤） */
    public Page<TrainingCohort> listCohorts(UUID planId, String status, int page, int size) {
        UUID companyId = accessService.requireCompanyId();
        if (status != null && !TrainingCohort.isValidStatus(status)) {
            throw new BusinessException(400, "未知的批次状态：" + status);
        }
        return cohortRepository.search(companyId, planId, status,
                PageRequest.of(Math.max(page, 1) - 1, clampSize(size)));
    }

    /**
     * 导师的「我的批次」列表。
     *
     * <p>没有这个入口，导师只有拿到 cohortId 才能进入复核，等于没有入口。</p>
     */
    public Page<TrainingCohort> listMyCohorts(int page, int size) {
        UUID companyId = accessService.requireCompanyId();
        UUID userId = TokenContext.get();
        if (userId == null) {
            throw new BusinessException(403, "无用户身份，无权查看批次");
        }
        return cohortRepository.findByCompanyIdAndMentorIdOrderByCreatedAtDesc(
                companyId, userId, PageRequest.of(Math.max(page, 1) - 1, clampSize(size)));
    }

    /** 批次详情：管理员看本企业全部，导师只看自己带的 */
    public CohortDetail getCohortDetail(UUID cohortId) {
        TrainingCohort cohort = accessService.assertCanReview(cohortId);
        List<TrainingAssignment> assignments =
                assignmentRepository.findByCohortIdOrderByCreatedAtAsc(cohortId);
        Map<UUID, User> learners = loadUsers(assignments.stream()
                .map(TrainingAssignment::getLearnerId).toList());
        TrainingPlan plan = planRepository.findById(cohort.getPlanId()).orElse(null);
        Map<UUID, CertificationView> certifications = loadCertifications(assignments.stream()
                .map(TrainingAssignment::getId).toList());
        return new CohortDetail(cohort, plan, assignments, learners, certifications);
    }

    // ==================== 分配 ====================

    /**
     * 批量分配学员。
     *
     * <p>一个事务内完成：先逐人校验并收集结果，再批量落库。校验不通过的人只影响自己，
     * 不会让整批失败；并发重复分配由唯一约束兜底并转成 409。</p>
     */
    @Transactional
    public BatchResult batchAssign(TrainingBatchAssignRequest req) {
        UUID companyId = accessService.requireCompanyId();
        // 行锁串行化同一批次的并发分配，避免唯一约束冲突把整批回滚
        TrainingCohort cohort = cohortRepository.findByIdForUpdate(req.getCohortId())
                .orElseThrow(() -> new BusinessException(404, "培训批次不存在或不可访问"));
        if (!companyId.equals(cohort.getCompanyId())) {
            throw new BusinessException(404, "培训批次不存在或不可访问");
        }
        if (!cohort.isAssignable()) {
            throw new BusinessException(409, "批次已结束，不能继续分配");
        }
        TrainingPlan plan = planRepository.findById(cohort.getPlanId()).orElse(null);
        if (plan != null && TrainingPlan.STATUS_RETIRED.equals(plan.getStatus())) {
            throw new BusinessException(409, "计划已退役，不能再分配新学员");
        }
        List<TrainingTask> tasks = taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(
                cohort.getPlanId(), cohort.getPlanVersion());
        if (tasks.isEmpty()) {
            throw new BusinessException(400, "该计划版本没有任务，无法分配");
        }
        int requiredTotal = (int) tasks.stream()
                .filter(t -> Boolean.TRUE.equals(t.getIsRequired())).count();

        List<UUID> learnerIds = req.getLearnerIds().stream().filter(Objects::nonNull).distinct().toList();
        Map<UUID, User> users = userRepository.findAllById(learnerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Set<UUID> existing = new HashSet<>(
                assignmentRepository.findExistingLearnerIds(cohort.getId(), learnerIds));

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = req.getDeadline() != null ? req.getDeadline() : cohort.getDeadline();
        List<AssignItem> results = new ArrayList<>(learnerIds.size());
        List<TrainingAssignment> assignments = new ArrayList<>();
        List<TrainingTaskRecord> records = new ArrayList<>();

        for (UUID learnerId : learnerIds) {
            User learner = users.get(learnerId);
            if (existing.contains(learnerId)) {
                results.add(new AssignItem(learnerId, nameOf(learner), "skipped", "已在本批次中"));
                continue;
            }
            if (learner == null || !companyId.equals(learner.getCompanyId())) {
                results.add(new AssignItem(learnerId, null, "failed", "用户不存在或不属于本企业"));
                continue;
            }
            if (!Boolean.TRUE.equals(learner.getIsActive())) {
                results.add(new AssignItem(learnerId, learner.getName(), "skipped", "账号已停用"));
                continue;
            }

            UUID assignmentId = UUID.randomUUID();
            assignments.add(TrainingAssignment.builder()
                    .id(assignmentId)
                    .companyId(companyId)
                    .cohortId(cohort.getId())
                    .learnerId(learnerId)
                    .status(TrainingAssignment.STATUS_NOT_STARTED)
                    .totalTasks(tasks.size())
                    .requiredTotal(requiredTotal)
                    .completedTasks(0)
                    .requiredPassed(0)
                    .passedTasks(0)
                    .deadline(deadline)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            for (TrainingTask task : tasks) {
                String initial = hasPrerequisites(task)
                        ? TrainingTaskRecord.STATUS_LOCKED
                        : TrainingTaskRecord.STATUS_AVAILABLE;
                records.add(TrainingTaskRecord.builder()
                        .id(UUID.randomUUID())
                        .assignmentId(assignmentId)
                        .taskId(task.getId())
                        .status(initial)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
            }
            results.add(new AssignItem(learnerId, learner.getName(), "assigned", null));
        }

        if (!assignments.isEmpty()) {
            try {
                assignmentRepository.saveAll(assignments);
                taskRecordRepository.saveAll(records);
            } catch (DataIntegrityViolationException e) {
                log.warn("批量分配并发冲突 cohortId={}", cohort.getId(), e);
                throw new BusinessException(409, "并发分配冲突，请刷新后重试");
            }
        }

        int assigned = (int) results.stream().filter(r -> "assigned".equals(r.status())).count();
        int skipped = (int) results.stream().filter(r -> "skipped".equals(r.status())).count();
        int failed = (int) results.stream().filter(r -> "failed".equals(r.status())).count();
        log.info("批量分配完成 cohortId={}, 成功={}, 跳过={}, 失败={}",
                cohort.getId(), assigned, skipped, failed);
        return new BatchResult(cohort.getId(), assigned, skipped, failed, results);
    }

    /**
     * 改绑到另一个批次（管理端兜底，不做常规 UI）。
     *
     * <p>强制 {@code force=true} + 理由，旧分配置 cancelled 保留证据，
     * 新分配按目标批次冻结的任务集重建进度行；跨企业一律 404。</p>
     */
    @Transactional
    public TrainingAssignment rebind(UUID assignmentId, UUID targetCohortId,
                                     boolean force, String reason) {
        if (!force) {
            throw new BusinessException(400, "改绑是高危操作，必须显式 force=true");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw new BusinessException(400, "改绑必须填写至少 5 个字的理由");
        }
        UUID companyId = accessService.requireCompanyId();
        TrainingAssignment old = assignmentRepository.findByIdAndCompanyId(assignmentId, companyId)
                .orElseThrow(() -> new BusinessException(404, "培训分配不存在或不可访问"));
        if (TrainingAssignment.STATUS_CANCELLED.equals(old.getStatus())) {
            throw new BusinessException(409, "该分配已取消，不能改绑");
        }
        TrainingCohort target = cohortRepository.findByIdForUpdate(targetCohortId)
                .orElseThrow(() -> new BusinessException(404, "培训批次不存在或不可访问"));
        if (!companyId.equals(target.getCompanyId())) {
            throw new BusinessException(404, "培训批次不存在或不可访问");
        }
        if (!target.isAssignable()) {
            throw new BusinessException(409, "目标批次已结束，不能改绑");
        }
        if (Objects.equals(old.getCohortId(), targetCohortId)) {
            throw new BusinessException(409, "目标批次与当前批次相同");
        }
        Set<UUID> existing = new HashSet<>(assignmentRepository.findExistingLearnerIds(
                targetCohortId, List.of(old.getLearnerId())));
        if (!existing.isEmpty()) {
            throw new BusinessException(409, "该学员已在目标批次中");
        }
        TrainingPlan plan = planRepository.findById(target.getPlanId())
                .orElseThrow(() -> new BusinessException(404, "培训计划不存在"));
        if (TrainingPlan.STATUS_RETIRED.equals(plan.getStatus())) {
            throw new BusinessException(409, "计划已退役，不能改绑");
        }
        List<TrainingTask> tasks = taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(
                target.getPlanId(), target.getPlanVersion());
        if (tasks.isEmpty()) {
            throw new BusinessException(400, "目标批次没有任务，无法改绑");
        }

        int cancelled = assignmentRepository.cancelIfOpen(assignmentId);
        if (cancelled == 0) {
            throw new BusinessException(409, "该分配已结束，不能改绑");
        }
        old.setStatus(TrainingAssignment.STATUS_CANCELLED);

        LocalDateTime now = LocalDateTime.now();
        int requiredTotal = (int) tasks.stream()
                .filter(t -> Boolean.TRUE.equals(t.getIsRequired())).count();
        UUID newId = UUID.randomUUID();
        TrainingAssignment created = TrainingAssignment.builder()
                .id(newId)
                .companyId(companyId)
                .cohortId(target.getId())
                .learnerId(old.getLearnerId())
                .status(TrainingAssignment.STATUS_NOT_STARTED)
                .totalTasks(tasks.size())
                .requiredTotal(requiredTotal)
                .completedTasks(0)
                .requiredPassed(0)
                .passedTasks(0)
                .deadline(target.getDeadline())
                .createdAt(now)
                .updatedAt(now)
                .build();
        List<TrainingTaskRecord> records = new ArrayList<>(tasks.size());
        for (TrainingTask task : tasks) {
            records.add(TrainingTaskRecord.builder()
                    .id(UUID.randomUUID())
                    .assignmentId(newId)
                    .taskId(task.getId())
                    .status(hasPrerequisites(task)
                            ? TrainingTaskRecord.STATUS_LOCKED : TrainingTaskRecord.STATUS_AVAILABLE)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        try {
            assignmentRepository.saveAndFlush(created);
            taskRecordRepository.saveAll(records);
        } catch (DataIntegrityViolationException e) {
            log.warn("改绑并发冲突 assignmentId={} targetCohortId={}", assignmentId, targetCohortId, e);
            throw new BusinessException(409, "学员已在目标批次中，请刷新后重试");
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("assignmentId", assignmentId.toString());
        detail.put("targetAssignmentId", newId.toString());
        detail.put("fromCohortId", String.valueOf(old.getCohortId()));
        detail.put("toCohortId", targetCohortId.toString());
        detail.put("reason", reason.trim());
        auditService.record("training.assignment.rebind", "training_assignment", assignmentId, detail);
        notificationService.notifyLearner(created, "training_reassigned", "培训安排已调整",
                "你已被调整到批次「" + target.getName() + "」，请按新安排完成任务",
                "/training", "rebind:" + assignmentId);
        return created;
    }

    /** 取消分配：保留历史，不再推进 */
    @Transactional
    public TrainingAssignment cancelAssignment(UUID assignmentId) {
        UUID companyId = accessService.requireCompanyId();
        TrainingAssignment assignment = assignmentRepository
                .findByIdAndCompanyId(assignmentId, companyId)
                .orElseThrow(() -> new BusinessException(404, "培训分配不存在或不可访问"));
        // 条件更新：与认证并发时，已认证的分配不能被取消覆盖
        int updated = assignmentRepository.cancelIfOpen(assignmentId);
        if (updated == 0) {
            throw new BusinessException(409, "该分配已结束，无需取消");
        }
        assignment.setStatus(TrainingAssignment.STATUS_CANCELLED);
        return assignment;
    }

    /** 我的培训：只返回本人的分配 */
    public MyAssignments myAssignments(int page, int size) {
        UUID userId = TokenContext.get();
        if (userId == null) {
            throw new BusinessException(403, "未登录");
        }
        UUID companyId = accessService.requireCompanyId();
        Page<TrainingAssignment> result = assignmentRepository
                .findByLearnerIdAndCompanyIdOrderByCreatedAtDesc(
                        userId, companyId, PageRequest.of(Math.max(page, 1) - 1, clampSize(size)));

        List<UUID> cohortIds = result.getContent().stream()
                .map(TrainingAssignment::getCohortId).distinct().toList();
        Map<UUID, TrainingCohort> cohorts = cohortIds.isEmpty() ? Map.of()
                : cohortRepository.findAllById(cohortIds).stream()
                    .collect(Collectors.toMap(TrainingCohort::getId, Function.identity()));

        List<UUID> planIds = cohorts.values().stream()
                .map(TrainingCohort::getPlanId).distinct().toList();
        Map<UUID, String> planNames = planIds.isEmpty() ? Map.of()
                : planRepository.findAllById(planIds).stream()
                    .collect(Collectors.toMap(TrainingPlan::getId, TrainingPlan::getName));

        List<UUID> assignmentIds = result.getContent().stream()
                .map(TrainingAssignment::getId).toList();
        return new MyAssignments(result, cohorts, planNames, loadCertifications(assignmentIds));
    }

    // ==================== 进度 ====================

    /** 批次进度（兼容旧调用：第一页全量，最多 500 人） */
    public CohortProgress cohortProgress(UUID cohortId) {
        return cohortProgress(cohortId, 1, 500, null, null);
    }

    /**
     * 批次进度：当前页学员 + 全局汇总 + 分页元数据。
     *
     * <p>summary 用聚合 SQL 计算，不随分页变化；records/attempts/certifications 只查当前页，
     * 避免大班全量加载。</p>
     */
    public CohortProgress cohortProgress(UUID cohortId, int page, int size,
                                         String status, String keyword) {
        TrainingCohort cohort = accessService.assertCanReview(cohortId);
        int safeSize = Math.min(Math.max(size, 1), 200);
        PageRequest pageable = PageRequest.of(Math.max(page, 1) - 1, safeSize);
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim();
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        Page<TrainingAssignment> pageResult = normalizedStatus == null && normalizedKeyword == null
                ? assignmentRepository.findByCohortIdOrderByCreatedAtAscIdAsc(cohortId, pageable)
                : assignmentRepository.searchCohortMembers(
                        cohortId, normalizedStatus, normalizedKeyword, pageable);
        List<TrainingAssignment> assignments = pageResult.getContent();
        Map<UUID, User> users = loadUsers(assignments.stream()
                .map(TrainingAssignment::getLearnerId).toList());

        List<UUID> assignmentIds = assignments.stream().map(TrainingAssignment::getId).toList();
        Map<UUID, List<TrainingTaskRecord>> recordsByAssignment = new HashMap<>();
        if (!assignmentIds.isEmpty()) {
            for (TrainingTaskRecord record : taskRecordRepository
                    .findByAssignmentIdIn(assignmentIds)) {
                recordsByAssignment.computeIfAbsent(record.getAssignmentId(),
                        k -> new ArrayList<>()).add(record);
            }
        }
        List<TrainingTask> tasks = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(
                        cohort.getPlanId(), cohort.getPlanVersion());

        // 待复核起始时间：按分配取最早一条 pending_review 尝试（聚合投影，不加载大 JSONB）
        Map<UUID, LocalDateTime> oldestPendingByAssignment = new HashMap<>();
        if (!assignmentIds.isEmpty()) {
            for (Object[] row : attemptRepository
                    .findOldestPendingSubmitByAssignmentIds(assignmentIds)) {
                oldestPendingByAssignment.put((UUID) row[0], (LocalDateTime) row[1]);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minusHours(Math.max(pendingReviewThresholdHours, 1));
        boolean superAdmin = companyScopeService.isUnrestrictedDataScope();
        UUID viewerId = TokenContext.get();
        List<LearnerProgress> learners = new ArrayList<>(assignments.size());
        for (TrainingAssignment a : assignments) {
            // 待认证是「主管还没确认」，不是学员逾期；逾期只算仍在学习中的分配
            boolean overdue = a.getDeadline() != null
                    && a.getDeadline().isBefore(now)
                    && (TrainingAssignment.STATUS_NOT_STARTED.equals(a.getStatus())
                        || TrainingAssignment.STATUS_IN_PROGRESS.equals(a.getStatus()));
            User user = users.get(a.getLearnerId());

            // 补训轮：只看每个任务的最新一轮，历史失败轮不参与看板结论
            Map<UUID, TrainingTaskRecord> latest =
                    aggregator.latestByTask(recordsByAssignment.getOrDefault(a.getId(), List.of()));
            Map<UUID, String> statusByTask = new HashMap<>();
            boolean pendingReview = false;
            for (TrainingTaskRecord record : latest.values()) {
                statusByTask.put(record.getTaskId(), record.getStatus());
                pendingReview |= TrainingTaskRecord.STATUS_PENDING_REVIEW.equals(record.getStatus());
            }
            List<CertificationBlocker> blockers = new ArrayList<>();
            for (TrainingTask task : tasks) {
                if (!Boolean.TRUE.equals(task.getIsRequired())) {
                    continue;
                }
                String taskStatus = statusByTask.get(task.getId());
                if (!TrainingTaskRecord.STATUS_PASSED.equals(taskStatus)
                        && !TrainingTaskRecord.STATUS_WAIVED.equals(taskStatus)) {
                    blockers.add(new CertificationBlocker(task.getId(), task.getTitle(),
                            taskStatus == null ? "not_started" : taskStatus));
                }
            }
            boolean self = !superAdmin && Objects.equals(viewerId, a.getLearnerId());
            boolean canCertify = TrainingAssignment.STATUS_PENDING_CERTIFICATION.equals(a.getStatus())
                    && blockers.isEmpty() && !self;
            String blockedReason = null;
            if (TrainingAssignment.STATUS_CERTIFIED.equals(a.getStatus())) {
                blockedReason = "already_certified";
            } else if (TrainingAssignment.STATUS_CANCELLED.equals(a.getStatus())) {
                blockedReason = "cancelled";
            } else if (!TrainingAssignment.STATUS_PENDING_CERTIFICATION.equals(a.getStatus())) {
                blockedReason = "not_pending";
            } else if (!blockers.isEmpty()) {
                blockedReason = "required_not_passed";
            } else if (self) {
                blockedReason = "self";
            }

            LocalDateTime oldestPending = oldestPendingByAssignment.get(a.getId());
            Long pendingHours = oldestPending == null ? null
                    : java.time.Duration.between(oldestPending, now).toHours();
            boolean pendingReviewOverdue = oldestPending != null && oldestPending.isBefore(staleBefore);

            learners.add(new LearnerProgress(
                    a.getId(), a.getLearnerId(), user != null ? user.getName() : null,
                    a.getStatus(), nz(a.getTotalTasks()), nz(a.getRequiredTotal()),
                    nz(a.getCompletedTasks()), nz(a.getRequiredPassed()), nz(a.getPassedTasks()),
                    a.getDeadline(), overdue, pendingReview, pendingReviewOverdue, pendingHours,
                    canCertify, blockedReason, blockers));
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        List<Object[]> statusCounts = assignmentRepository.cohortStatusCounts(cohortId);
        Object[] counts = statusCounts.isEmpty() ? new Object[6] : statusCounts.get(0);
        summary.put("total", countValue(counts, 0));
        summary.put("notStarted", countValue(counts, 1));
        summary.put("inProgress", countValue(counts, 2));
        summary.put("pendingCertification", countValue(counts, 3));
        summary.put("certified", countValue(counts, 4));
        summary.put("cancelled", countValue(counts, 5));
        summary.put("overdue", assignmentRepository.countOverdueByCohort(cohortId));
        summary.put("pendingReview", assignmentRepository.countPendingReviewByCohort(cohortId));
        summary.put("pendingReviewOverdue",
                assignmentRepository.countStalePendingReviewByCohort(cohortId, staleBefore));
        // 计划名随进度一起下发，前端无需再拉一次全量批次详情
        planRepository.findById(cohort.getPlanId())
                .ifPresent(plan -> summary.put("planName", plan.getName()));
        return new CohortProgress(cohort, learners, summary,
                loadCertifications(assignmentIds),
                Math.max(page, 1), safeSize, pageResult.getTotalElements(), pageResult.getTotalPages());
    }

    // ==================== 辅助 ====================

    /** 批量加载认证视图（含确认人姓名），一次查询认证 + 一次查询用户 */
    private Map<UUID, CertificationView> loadCertifications(List<UUID> assignmentIds) {
        if (assignmentIds.isEmpty()) {
            return Map.of();
        }
        List<TrainingCertification> history =
                certificationRepository.findByAssignmentIdInOrderByCreatedAtDesc(assignmentIds);
        if (history.isEmpty()) {
            return Map.of();
        }
        // 每个分配只取一条：优先 confirmed，否则最新一条（revoked 历史也要让列表显示「已撤销」）
        Map<UUID, TrainingCertification> chosen = new HashMap<>();
        for (TrainingCertification certification : history) {
            TrainingCertification current = chosen.get(certification.getAssignmentId());
            if (current == null || certification.isConfirmed()) {
                chosen.put(certification.getAssignmentId(), certification);
            }
        }
        List<UUID> reviewerIds = new ArrayList<>();
        for (TrainingCertification certification : chosen.values()) {
            if (certification.getConfirmedBy() != null) {
                reviewerIds.add(certification.getConfirmedBy());
            }
            if (certification.getRevokedBy() != null) {
                reviewerIds.add(certification.getRevokedBy());
            }
        }
        Map<UUID, String> names = new HashMap<>();
        if (!reviewerIds.isEmpty()) {
            for (User reviewer : userRepository.findAllById(reviewerIds.stream().distinct().toList())) {
                names.put(reviewer.getId(), reviewer.getName());
            }
        }
        Map<UUID, CertificationView> views = new HashMap<>();
        for (TrainingCertification certification : chosen.values()) {
            String reviewerName = certification.getConfirmedBy() == null ? null
                    : names.get(certification.getConfirmedBy());
            String revokedName = certification.getRevokedBy() == null ? null
                    : names.get(certification.getRevokedBy());
            views.put(certification.getAssignmentId(),
                    CertificationView.of(certification, reviewerName, revokedName));
        }
        return views;
    }

    private long countValue(Object[] row, int index) {
        if (row == null || index >= row.length || row[index] == null) {
            return 0L;
        }
        return ((Number) row[index]).longValue();
    }

    private Map<UUID, User> loadUsers(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, User> map = new HashMap<>();
        for (User user : userRepository.findAllById(ids)) {
            map.put(user.getId(), user);
        }
        return map;
    }

    private String nameOf(User user) {
        return user == null ? null : user.getName();
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }

    /** 分页大小上限，避免一次拉全表 */
    private int clampSize(int size) {
        return Math.max(1, Math.min(size, 200));
    }

    /** 前置为空 → 初始可用；有前置 → 初始锁定 */
    private boolean hasPrerequisites(TrainingTask task) {
        String json = task.getPrerequisites();
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return false;
        }
        try {
            List<String> ids = objectMapper.readValue(json, new TypeReference<List<String>>() { });
            return !ids.isEmpty();
        } catch (Exception e) {
            log.warn("任务 {} 的前置 JSON 无法解析，按锁定处理：{}", task.getId(), json);
            return true;
        }
    }
}
