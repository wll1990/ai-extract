package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.TrainingPhaseRequest;
import com.aiextract.dto.TrainingPlanRequest;
import com.aiextract.dto.TrainingTaskOrderRequest;
import com.aiextract.dto.TrainingTaskRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationDatasetVersion;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.TrainingPlan;
import com.aiextract.model.TrainingPlanVersion;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.EvaluationDatasetVersionRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.TrainingPlanRepository;
import com.aiextract.repository.TrainingPlanVersionRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 培训计划服务 —— 计划/版本/任务/发布的生命周期。
 *
 * <p>三条规则贯穿全类：</p>
 * <ol>
 *   <li><b>版本即编辑边界</b>：只有 draft 版本的任务可增删改；published 版本只读，
 *       要改必须「新建版本」从已发布版复制。批次绑定的正是冻结版本，因此改计划不会
 *       重置已分配学员的进度。</li>
 *   <li><b>发布前校验资源</b>：任务引用的知识/案例/分身/题集版本都必须仍可读且可用，
 *       否则发出去的计划学员点开就是死链。</li>
 *   <li><b>前置关系无环</b>：前置存在性在写入时校验，成环在发布时整体校验
 *       （单条写入无法发现环，只有拿到整版任务才能判断）。</li>
 * </ol>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingPlanService {

    /** 单场考试题数上限（与开考抽题共用同一常量） */
    private static final int MAX_EXAM_ITEMS = TrainingTask.MAX_EXAM_ITEMS;

    private final TrainingPlanRepository planRepository;
    private final TrainingPlanVersionRepository versionRepository;
    private final TrainingTaskRepository taskRepository;
    private final TrainingContentSnapshotService snapshotService;
    private final EvaluationDatasetVersionRepository datasetVersionRepository;
    private final EvaluationItemRepository itemRepository;
    private final CompanyScopeService companyScopeService;
    private final ObjectMapper objectMapper;

    /** 计划详情聚合：计划 + 版本历史 + 当前版本任务树（按阶段、顺序） */
    public record PlanDetail(TrainingPlan plan, List<TrainingPlanVersion> versions, List<TrainingTask> tasks) {
    }

    // ==================== 计划 ====================

    /** 企业内计划列表（关键字匹配名称） */
    public Page<TrainingPlan> listPlans(String status, String keyword, int page, int size) {
        UUID companyId = currentCompanyId();
        if (status != null && !TrainingPlan.isValidStatus(status)) {
            throw new BusinessException(400, "未知的计划状态：" + status);
        }
        String pattern = (keyword == null || keyword.isBlank()) ? null
                : "%" + keyword.trim().toLowerCase() + "%";
        return planRepository.search(companyId, status, pattern,
                PageRequest.of(Math.max(page, 1) - 1, Math.max(1, Math.min(size, 200))));
    }

    /** 计划详情（含版本历史与当前版本任务树） */
    public PlanDetail getPlanDetail(UUID planId) {
        TrainingPlan plan = requirePlan(planId);
        List<TrainingPlanVersion> versions = versionRepository.findByPlanIdOrderByVersionDesc(planId);
        List<TrainingTask> tasks = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, plan.getCurrentVersion());
        return new PlanDetail(plan, versions, tasks);
    }

    /**
     * 学员视角预览：默认取最新已发布版本（学员真正看到的），可显式指定版本。
     *
     * <p>没有已发布版本时回退到当前草稿，前端据此提示「尚未发布，学员看不到」。</p>
     */
    public PlanDetail getPlanPreview(UUID planId, Integer version) {
        TrainingPlan plan = requirePlan(planId);
        List<TrainingPlanVersion> versions = versionRepository.findByPlanIdOrderByVersionDesc(planId);
        Integer latestPublished = versions.stream()
                .filter(TrainingPlanVersion::isPublished)
                .map(TrainingPlanVersion::getVersion)
                .max(Integer::compareTo).orElse(null);
        int target = version != null ? version
                : (latestPublished != null ? latestPublished : plan.getCurrentVersion());
        boolean exists = versions.stream().anyMatch(v -> Objects.equals(v.getVersion(), target));
        if (!exists) {
            throw new BusinessException(404, "计划版本不存在");
        }
        List<TrainingTask> tasks = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, target);
        return new PlanDetail(plan, versions, tasks);
    }

    // ==================== 版本对比（TR-10） ====================

    /** 单个字段差异 */
    public record FieldDiff(String field, Object from, Object to) {
    }

    /** 单个任务差异（按标题匹配，标题相同视为同一任务） */
    public record TaskDiff(String title, Integer phaseNo, List<FieldDiff> changes) {
    }

    /** 两个版本的差异：新增 / 删除 / 修改 */
    public record VersionDiff(int fromVersion, int toVersion,
                              List<TaskDiff> added, List<TaskDiff> removed, List<TaskDiff> changed) {
    }

    /**
     * 对比两个版本的任务差异。
     *
     * <p>复制版本会给任务生成新 ID，因此按「任务标题」匹配；同版本内标题重复时
     * 以先出现的一条为准（正常配置不会重复）。</p>
     */
    public VersionDiff diffVersions(UUID planId, int fromVersion, int toVersion) {
        requirePlan(planId);
        if (fromVersion == toVersion) {
            throw new BusinessException(400, "对比的两个版本不能相同");
        }
        List<TrainingTask> fromTasks = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, fromVersion);
        List<TrainingTask> toTasks = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, toVersion);
        if (fromTasks.isEmpty() && toTasks.isEmpty()) {
            throw new BusinessException(404, "版本不存在或没有任务");
        }

        Map<String, TrainingTask> fromByTitle = new LinkedHashMap<>();
        for (TrainingTask task : fromTasks) {
            fromByTitle.putIfAbsent(task.getTitle(), task);
        }
        Map<String, TrainingTask> toByTitle = new LinkedHashMap<>();
        for (TrainingTask task : toTasks) {
            toByTitle.putIfAbsent(task.getTitle(), task);
        }

        List<TaskDiff> added = new ArrayList<>();
        for (TrainingTask task : toTasks) {
            if (!fromByTitle.containsKey(task.getTitle())
                    && added.stream().noneMatch(a -> a.title().equals(task.getTitle()))) {
                added.add(new TaskDiff(task.getTitle(), task.getPhaseNo(), List.of()));
            }
        }

        List<TaskDiff> removed = new ArrayList<>();
        for (TrainingTask task : fromTasks) {
            if (!toByTitle.containsKey(task.getTitle())
                    && removed.stream().noneMatch(r -> r.title().equals(task.getTitle()))) {
                removed.add(new TaskDiff(task.getTitle(), task.getPhaseNo(), List.of()));
            }
        }

        List<TaskDiff> changed = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TrainingTask before : fromTasks) {
            if (!seen.add(before.getTitle())) {
                continue;
            }
            TrainingTask after = toByTitle.get(before.getTitle());
            if (after == null) {
                continue;
            }
            List<FieldDiff> changes = new ArrayList<>();
            addIfChanged(changes, "type", before.getType(), after.getType());
            addIfChanged(changes, "phaseNo", before.getPhaseNo(), after.getPhaseNo());
            addIfChanged(changes, "isRequired", before.getIsRequired(), after.getIsRequired());
            addIfChanged(changes, "resourceType", before.getResourceType(), after.getResourceType());
            addIfChanged(changes, "resourceId", before.getResourceId(), after.getResourceId());
            addIfChanged(changes, "sceneTag", before.getSceneTag(), after.getSceneTag());
            addIfChanged(changes, "allowHints", before.getAllowHints(), after.getAllowHints());
            addIfChanged(changes, "examDatasetId", before.getExamDatasetId(), after.getExamDatasetId());
            addIfChanged(changes, "examDatasetVersion",
                    before.getExamDatasetVersion(), after.getExamDatasetVersion());
            addIfChanged(changes, "examItemCount", before.getExamItemCount(), after.getExamItemCount());
            addIfChanged(changes, "examDurationMinutes",
                    before.getExamDurationMinutes(), after.getExamDurationMinutes());
            addIfChanged(changes, "passCriteria", before.getPassCriteria(), after.getPassCriteria());
            addIfChanged(changes, "maxAttempts", before.getMaxAttempts(), after.getMaxAttempts());
            addIfChanged(changes, "deadlinePolicy",
                    before.getDeadlinePolicy(), after.getDeadlinePolicy());
            addIfChanged(changes, "prerequisites",
                    before.getPrerequisites(), after.getPrerequisites());
            if (!changes.isEmpty()) {
                changed.add(new TaskDiff(before.getTitle(), after.getPhaseNo(), changes));
            }
        }
        return new VersionDiff(fromVersion, toVersion, added, removed, changed);
    }

    private void addIfChanged(List<FieldDiff> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(new FieldDiff(field, before, after));
        }
    }

    /** 发布预检：与 publish 共用同一份 blocker 收集逻辑，只读 */
    @Transactional(readOnly = true)
    public List<PublishBlocker> readiness(UUID planId) {
        TrainingPlan plan = requirePlan(planId);
        TrainingPlanVersion version = requireEditableVersion(plan);
        List<TrainingTask> tasks = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, version.getVersion());
        if (tasks.isEmpty()) {
            return List.of(new PublishBlocker(null, null, null, "计划至少需要一个任务才能发布"));
        }
        return collectPublishBlockers(tasks);
    }

    /** 新建计划：同时创建 v1 草稿版本，保证「计划必有可编辑版本」的不变式 */
    @Transactional
    public TrainingPlan createPlan(TrainingPlanRequest req) {
        UUID companyId = currentCompanyId();
        UUID userId = TokenContext.get();
        LocalDateTime now = LocalDateTime.now();

        TrainingPlan plan = TrainingPlan.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .name(req.getName().trim())
                .description(req.getDescription())
                .status(TrainingPlan.STATUS_DRAFT)
                .currentVersion(1)
                .createdBy(userId)
                .createdAt(now)
                .updatedAt(now)
                .build();
        planRepository.save(plan);

        versionRepository.save(TrainingPlanVersion.builder()
                .id(UUID.randomUUID())
                .planId(plan.getId())
                .version(1)
                .status(TrainingPlanVersion.STATUS_DRAFT)
                .taskCount(0)
                .createdBy(userId)
                .createdAt(now)
                .build());
        return plan;
    }

    /** 更新计划基本信息（版本号乐观锁） */
    @Transactional
    public TrainingPlan updatePlan(UUID planId, TrainingPlanRequest req) {
        TrainingPlan plan = requirePlan(planId);
        if (TrainingPlan.STATUS_RETIRED.equals(plan.getStatus())) {
            throw new BusinessException(409, "计划已退役，不能修改");
        }
        if (req.getExpectedVersion() != null
                && !Objects.equals(req.getExpectedVersion(), plan.getCurrentVersion())) {
            throw new BusinessException(409, "计划已被他人修改，请刷新后重试");
        }
        plan.setName(req.getName().trim());
        plan.setDescription(req.getDescription());
        return planRepository.save(plan);
    }

    /**
     * 从当前已发布版本复制出新草稿版本。
     *
     * <p>任务 id 会重新生成，因此前置引用必须同步重映射，否则新版本里全是悬空前置。</p>
     */
    @Transactional
    public TrainingPlanVersion createVersion(UUID planId) {
        TrainingPlan plan = requirePlan(planId);
        if (TrainingPlan.STATUS_RETIRED.equals(plan.getStatus())) {
            throw new BusinessException(409, "计划已退役，不能创建新版本");
        }
        int latest = versionRepository.findMaxVersion(planId);
        TrainingPlanVersion current = versionRepository.findByPlanIdAndVersion(planId, latest)
                .orElseThrow(() -> new BusinessException(500, "计划版本数据缺失"));
        if (!current.isPublished()) {
            throw new BusinessException(409, "当前版本尚未发布，无需创建新版本");
        }

        int next = latest + 1;
        LocalDateTime now = LocalDateTime.now();
        TrainingPlanVersion draft = TrainingPlanVersion.builder()
                .id(UUID.randomUUID())
                .planId(planId)
                .version(next)
                .status(TrainingPlanVersion.STATUS_DRAFT)
                .taskCount(0)
                .createdBy(TokenContext.get())
                .createdAt(now)
                .build();
        try {
            versionRepository.saveAndFlush(draft);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw BusinessException.conflict("TRAINING_PLAN_VERSION_CONFLICT",
                    "新版本已被其他人创建，请刷新后重试");
        }

        List<TrainingTask> source = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, latest);
        Map<UUID, UUID> idMap = new HashMap<>();
        for (TrainingTask t : source) {
            idMap.put(t.getId(), UUID.randomUUID());
        }
        List<TrainingTask> copies = new ArrayList<>();
        for (TrainingTask t : source) {
            List<UUID> remapped = new ArrayList<>();
            for (UUID prereq : parsePrerequisites(t)) {
                UUID mapped = idMap.get(prereq);
                if (mapped != null) {
                    remapped.add(mapped);
                }
            }
            copies.add(TrainingTask.builder()
                    .id(idMap.get(t.getId()))
                    .planId(planId)
                    .version(next)
                    .phaseNo(t.getPhaseNo())
                    .phaseName(t.getPhaseName())
                    .type(t.getType())
                    .title(t.getTitle())
                    .description(t.getDescription())
                    .isRequired(t.getIsRequired())
                    .orderNo(t.getOrderNo())
                    .prerequisites(writePrerequisites(remapped))
                    .resourceType(t.getResourceType())
                    .resourceId(t.getResourceId())
                    .sceneTag(t.getSceneTag())
                    .allowHints(t.getAllowHints())
                    .examDatasetId(t.getExamDatasetId())
                    .examDatasetVersion(t.getExamDatasetVersion())
                    .examItemCount(t.getExamItemCount())
                    .examDurationMinutes(t.getExamDurationMinutes())
                    .passCriteria(t.getPassCriteria())
                    .maxAttempts(t.getMaxAttempts())
                    .deadlinePolicy(t.getDeadlinePolicy())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        taskRepository.saveAll(copies);

        // 计划保持 published：旧版本仍可分配，新草稿版本用于下一次迭代
        plan.setCurrentVersion(next);
        planRepository.save(plan);
        return draft;
    }

    // ==================== 任务 ====================

    /** 新增任务（仅草稿版本） */
    @Transactional
    public TrainingTask addTask(UUID planId, TrainingTaskRequest req) {
        TrainingPlan plan = requirePlan(planId);
        TrainingPlanVersion version = requireEditableVersion(plan);
        validateTask(req, plan, version.getVersion(), null);

        int phaseNo = req.getPhaseNo() != null ? req.getPhaseNo()
                : Math.max(1, taskRepository.findMaxPhaseNo(planId, version.getVersion()));
        int orderNo = req.getOrderNo() != null ? req.getOrderNo()
                : taskRepository.findMaxOrderNo(planId, version.getVersion(), phaseNo) + 1;
        LocalDateTime now = LocalDateTime.now();

        TrainingTask task = TrainingTask.builder()
                .id(UUID.randomUUID())
                .planId(planId)
                .version(version.getVersion())
                .phaseNo(phaseNo)
                .phaseName(req.getPhaseName())
                .type(req.getType())
                .title(req.getTitle().trim())
                .description(req.getDescription())
                .isRequired(req.getIsRequired() == null ? Boolean.TRUE : req.getIsRequired())
                .orderNo(orderNo)
                .prerequisites(writePrerequisites(nullToEmpty(req.getPrerequisites())))
                .resourceType(req.getResourceType())
                .resourceId(req.getResourceId())
                .sceneTag(req.getSceneTag())
                .allowHints(resolveAllowHints(req))
                .examDatasetId(req.getExamDatasetId())
                .examDatasetVersion(req.getExamDatasetVersion())
                .examItemCount(req.getExamItemCount())
                .examDurationMinutes(req.getExamDurationMinutes())
                .passCriteria(req.getPassCriteria())
                .maxAttempts(req.getMaxAttempts() == null ? 1 : req.getMaxAttempts())
                .deadlinePolicy(req.getDeadlinePolicy() == null
                        ? TrainingTask.DEADLINE_AUTO_SUBMIT : req.getDeadlinePolicy())
                // 微练场景卡在创建时由 AI 生成并冻结，发布时不再依赖资源实时内容
                .resourceSnapshot(TrainingTask.TYPE_DRILL.equals(req.getType())
                        ? req.getDrillScenario() : null)
                .snapshotVersion(TrainingTask.TYPE_DRILL.equals(req.getType()) ? 1 : null)
                .snapshotAt(TrainingTask.TYPE_DRILL.equals(req.getType()) ? now : null)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return taskRepository.save(task);
    }

    /** 修改任务（仅草稿版本） */
    @Transactional
    public TrainingTask updateTask(UUID planId, UUID taskId, TrainingTaskRequest req) {
        TrainingPlan plan = requirePlan(planId);
        TrainingPlanVersion version = requireEditableVersion(plan);
        TrainingTask task = taskRepository.findByIdAndPlanIdAndVersion(taskId, planId, version.getVersion())
                .orElseThrow(() -> new BusinessException(404, "任务不存在或不属于当前编辑版本"));
        validateTask(req, plan, version.getVersion(), taskId);

        task.setType(req.getType());
        task.setPhaseNo(req.getPhaseNo() == null ? task.getPhaseNo() : req.getPhaseNo());
        task.setPhaseName(req.getPhaseName());
        task.setTitle(req.getTitle().trim());
        task.setDescription(req.getDescription());
        if (req.getIsRequired() != null) {
            task.setIsRequired(req.getIsRequired());
        }
        if (req.getOrderNo() != null) {
            task.setOrderNo(req.getOrderNo());
        }
        task.setPrerequisites(writePrerequisites(nullToEmpty(req.getPrerequisites())));
        task.setResourceType(req.getResourceType());
        task.setResourceId(req.getResourceId());
        task.setSceneTag(req.getSceneTag());
        task.setAllowHints(resolveAllowHints(req));
        task.setExamDatasetId(req.getExamDatasetId());
        task.setExamDatasetVersion(req.getExamDatasetVersion());
        task.setExamItemCount(req.getExamItemCount());
        task.setExamDurationMinutes(req.getExamDurationMinutes());
        task.setPassCriteria(req.getPassCriteria());
        if (req.getMaxAttempts() != null) {
            task.setMaxAttempts(req.getMaxAttempts());
        }
        if (req.getDeadlinePolicy() != null) {
            task.setDeadlinePolicy(req.getDeadlinePolicy());
        }
        if (TrainingTask.TYPE_DRILL.equals(req.getType()) && req.getDrillScenario() != null
                && !req.getDrillScenario().isBlank()) {
            // 重新生成的场景卡替换旧快照；未传时保留已生成内容
            task.setResourceSnapshot(req.getDrillScenario());
            task.setSnapshotVersion((task.getSnapshotVersion() == null ? 0 : task.getSnapshotVersion()) + 1);
            task.setSnapshotAt(LocalDateTime.now());
        }
        return taskRepository.save(task);
    }

    /** 删除任务（仅草稿版本；被其他任务设为前置时禁止删除） */
    @Transactional
    public void deleteTask(UUID planId, UUID taskId) {
        TrainingPlan plan = requirePlan(planId);
        TrainingPlanVersion version = requireEditableVersion(plan);
        TrainingTask task = taskRepository.findByIdAndPlanIdAndVersion(taskId, planId, version.getVersion())
                .orElseThrow(() -> new BusinessException(404, "任务不存在或不属于当前编辑版本"));

        List<TrainingTask> siblings = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, version.getVersion());
        for (TrainingTask sibling : siblings) {
            if (parsePrerequisites(sibling).contains(taskId)) {
                throw new BusinessException(409,
                        "任务「" + sibling.getTitle() + "」把它设为前置，请先解除依赖再删除");
            }
        }
        taskRepository.delete(task);
    }

    /** 调整顺序：必须提交该版本完整的任务 ID 集合 */
    @Transactional
    public void reorderTasks(UUID planId, TrainingTaskOrderRequest req) {
        TrainingPlan plan = requirePlan(planId);
        TrainingPlanVersion version = requireEditableVersion(plan);
        List<TrainingTask> tasks = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, version.getVersion());

        Set<UUID> existing = new HashSet<>();
        Map<UUID, TrainingTask> byId = new LinkedHashMap<>();
        for (TrainingTask t : tasks) {
            existing.add(t.getId());
            byId.put(t.getId(), t);
        }
        Set<UUID> submitted = new HashSet<>(req.getTaskIds());
        if (submitted.size() != req.getTaskIds().size()) {
            throw new BusinessException(400, "任务 ID 列表存在重复项");
        }
        if (!submitted.equals(existing)) {
            throw new BusinessException(409, "任务 ID 集合与当前版本不一致，请刷新后重试");
        }

        // 按提交顺序，在每个阶段内重新编号；阶段归属不变
        Map<Integer, Integer> cursor = new HashMap<>();
        for (UUID id : req.getTaskIds()) {
            TrainingTask task = byId.get(id);
            int next = cursor.merge(task.getPhaseNo(), 1, Integer::sum);
            task.setOrderNo(next);
        }
        taskRepository.saveAll(tasks);
    }

    // ==================== 阶段 ====================

    /**
     * 阶段改名/重排（仅草稿版本）。
     *
     * <p>提交顺序即新顺序，后端重编 phase_no=1..n；阶段集合必须与任务中出现的集合一致，
     * 不允许凭空新增/删除阶段（删除最后一个任务后阶段自然消失）。</p>
     */
    @Transactional
    public List<TrainingTask> updatePhases(UUID planId, List<TrainingPhaseRequest.PhaseItem> phases) {
        TrainingPlan plan = requirePlan(planId);
        TrainingPlanVersion version = requireEditableVersion(plan);
        List<TrainingTask> tasks = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, version.getVersion());
        Set<Integer> existing = new HashSet<>();
        for (TrainingTask task : tasks) {
            existing.add(task.getPhaseNo());
        }
        Set<Integer> submitted = new HashSet<>();
        for (TrainingPhaseRequest.PhaseItem phase : phases) {
            submitted.add(phase.getPhaseNo());
        }
        if (submitted.size() != phases.size() || !submitted.equals(existing)) {
            throw BusinessException.conflict("PHASE_SET_MISMATCH",
                    "阶段集合与当前版本不一致，请刷新后重试");
        }
        Map<Integer, Integer> newNoByOld = new HashMap<>();
        Map<Integer, String> nameByOld = new HashMap<>();
        for (int i = 0; i < phases.size(); i++) {
            TrainingPhaseRequest.PhaseItem phase = phases.get(i);
            newNoByOld.put(phase.getPhaseNo(), i + 1);
            nameByOld.put(phase.getPhaseNo(), phase.getPhaseName());
        }
        for (TrainingTask task : tasks) {
            Integer oldNo = task.getPhaseNo();
            task.setPhaseNo(newNoByOld.get(oldNo));
            String name = nameByOld.get(oldNo);
            if (name != null && !name.isBlank()) {
                task.setPhaseName(name.trim());
            }
        }
        taskRepository.saveAll(tasks);
        return tasks;
    }

    // ==================== 发布 / 退役 ====================

    /**
     * 发布当前草稿版本：冻结任务集。
     *
     * <p>校验顺序：非空 → 前置存在且无环 → 资源仍可用（知识/案例/分身/题集版本）。</p>
     */
    @Transactional
    public TrainingPlan publish(UUID planId) {
        TrainingPlan plan = requirePlan(planId);
        TrainingPlanVersion version = requireEditableVersion(plan);
        List<TrainingTask> tasks = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, version.getVersion());

        if (tasks.isEmpty()) {
            throw new BusinessException(400, "计划至少需要一个任务才能发布");
        }
        List<PublishBlocker> blockers = collectPublishBlockers(tasks);
        if (!blockers.isEmpty()) {
            String detail = blockers.stream()
                    .map(b -> b.title() != null
                            ? "任务「" + b.title() + "」：" + b.reason()
                            : b.reason())
                    .collect(Collectors.joining("；"));
            throw new BusinessException(400, "发布校验未通过：" + detail);
        }

        // 发布即冻结阅读内容：资源后续下线/修改不影响已分配学员
        snapshotService.captureForPublish(tasks, plan.getCompanyId());
        taskRepository.saveAll(tasks);

        LocalDateTime now = LocalDateTime.now();
        version.setStatus(TrainingPlanVersion.STATUS_PUBLISHED);
        version.setPublishedAt(now);
        version.setTaskCount(tasks.size());
        versionRepository.save(version);

        plan.setStatus(TrainingPlan.STATUS_PUBLISHED);
        return planRepository.save(plan);
    }

    /** 退役：阻止新分配，不终止已有学习 */
    @Transactional
    public TrainingPlan retire(UUID planId) {
        TrainingPlan plan = requirePlan(planId);
        if (TrainingPlan.STATUS_RETIRED.equals(plan.getStatus())) {
            throw new BusinessException(409, "计划已退役");
        }
        if (!TrainingPlan.STATUS_PUBLISHED.equals(plan.getStatus())) {
            throw new BusinessException(409, "只有已发布的计划可以退役");
        }
        plan.setStatus(TrainingPlan.STATUS_RETIRED);
        return planRepository.save(plan);
    }

    // ==================== 校验 ====================

    private void validateTask(TrainingTaskRequest req, TrainingPlan plan, int version, UUID selfId) {
        if (!TrainingTask.isValidType(req.getType())) {
            throw new BusinessException(400, "未知的任务类型：" + req.getType());
        }
        if (req.getDeadlinePolicy() != null && !TrainingTask.isDeadlinePolicy(req.getDeadlinePolicy())) {
            throw new BusinessException(400, "未知的逾期策略：" + req.getDeadlinePolicy());
        }
        if (req.getMaxAttempts() != null && req.getMaxAttempts() < 1) {
            throw new BusinessException(400, "尝试次数至少为 1");
        }
        validatePassCriteria(req.getPassCriteria());
        if (TrainingTask.TYPE_PRACTICE.equals(req.getType())
                && hasMinPointCoverage(req.getPassCriteria())) {
            // 陪练没有金标要点，覆盖率恒为 1.0；配置了也不生效，属于静默误导
            throw new BusinessException(400, "陪练任务不支持 minPointCoverage（无金标要点）");
        }

        switch (req.getType()) {
            case TrainingTask.TYPE_READING -> validateReading(req);
            case TrainingTask.TYPE_PRACTICE -> validatePractice(req);
            case TrainingTask.TYPE_EXAM -> validateExam(req, version);
            case TrainingTask.TYPE_DRILL -> validateDrill(req);
            default -> throw new BusinessException(400, "未知的任务类型：" + req.getType());
        }
        validatePrerequisitesExist(req.getPrerequisites(), plan.getId(), version, selfId);
    }

    private void validateReading(TrainingTaskRequest req) {
        if (!TrainingTask.RESOURCE_KNOWLEDGE.equals(req.getResourceType())
                && !TrainingTask.RESOURCE_CASE.equals(req.getResourceType())) {
            throw new BusinessException(400, "阅读任务必须绑定知识或案例资源");
        }
        if (req.getResourceId() == null) {
            throw new BusinessException(400, "阅读任务缺少资源 ID");
        }
        if (TrainingTask.RESOURCE_KNOWLEDGE.equals(req.getResourceType())) {
            companyScopeService.assertKnowledgeReadable(req.getResourceId());
        } else {
            companyScopeService.assertCaseReadable(req.getResourceId());
        }
    }

    private void validatePractice(TrainingTaskRequest req) {
        if (!TrainingTask.RESOURCE_SKILL.equals(req.getResourceType())) {
            throw new BusinessException(400, "陪练任务必须绑定分身（resourceType=skill）");
        }
        if (req.getResourceId() == null) {
            throw new BusinessException(400, "陪练任务缺少分身 ID");
        }
        companyScopeService.assertSkillOwnership(req.getResourceId());
    }

    private void validateExam(TrainingTaskRequest req, int version) {
        if (req.getExamDatasetId() == null || req.getExamDatasetVersion() == null) {
            throw new BusinessException(400, "考试任务必须绑定题集与版本");
        }
        if (req.getExamDurationMinutes() == null || req.getExamDurationMinutes() <= 0) {
            throw new BusinessException(400, "考试任务必须设置考试时长（分钟）");
        }
        if (req.getExamItemCount() != null && req.getExamItemCount() <= 0) {
            throw new BusinessException(400, "抽题数必须大于 0");
        }
        companyScopeService.assertDatasetReadable(req.getExamDatasetId());
        EvaluationDatasetVersion datasetVersion = datasetVersionRepository
                .findByDatasetIdAndVersion(req.getExamDatasetId(), req.getExamDatasetVersion())
                .orElseThrow(() -> new BusinessException(400, "题集版本不存在"));
        if (!EvaluationDatasetVersion.STATUS_PUBLISHED.equals(datasetVersion.getStatus())) {
            throw new BusinessException(400, "考试题集版本必须已发布");
        }
        // 与开考时的抽题口径保持一致：只算 active 题目，否则发布能过、学员开考报"没有可用题目"
        long available = itemRepository.countByDatasetIdAndVersionAndStatus(
                req.getExamDatasetId(), req.getExamDatasetVersion(), EvaluationItem.STATUS_ACTIVE);
        if (available == 0) {
            throw new BusinessException(400, "考试题集版本没有题目");
        }
        if (req.getExamItemCount() != null && req.getExamItemCount() > MAX_EXAM_ITEMS) {
            throw new BusinessException(400, "单场考试最多 " + MAX_EXAM_ITEMS + " 题");
        }
        if (req.getExamItemCount() == null && available > MAX_EXAM_ITEMS) {
            // 不抽题 = 全量，题集过大时评分会逐题串行调用模型，必须要求显式抽题
            throw new BusinessException(400, "题集共 " + available + " 题，超过单场上限 "
                    + MAX_EXAM_ITEMS + "，请设置抽题数");
        }
        if (req.getExamItemCount() != null && req.getExamItemCount() > available) {
            throw new BusinessException(400,
                    "抽题数超过题集可用题数（" + available + " 题）");
        }
    }

    /**
     * 场景微练：必须绑定本企业可读的知识/案例/分身，且带 AI 生成好的场景卡。
     *
     * <p>场景卡由独立生成接口产出（AI 调用不在事务内），这里只做结构校验：
     * 至少 1 问、最多 3 问，每问必须有可核对的要点（rubric），否则评分会退化成
     * 「模型随便给分」。</p>
     */
    private void validateDrill(TrainingTaskRequest req) {
        if (req.getResourceId() == null
                || (!TrainingTask.RESOURCE_KNOWLEDGE.equals(req.getResourceType())
                    && !TrainingTask.RESOURCE_CASE.equals(req.getResourceType())
                    && !TrainingTask.RESOURCE_SKILL.equals(req.getResourceType()))) {
            throw new BusinessException(400, "微练必须绑定知识/案例/分身资源");
        }
        if (TrainingTask.RESOURCE_KNOWLEDGE.equals(req.getResourceType())) {
            companyScopeService.assertKnowledgeReadable(req.getResourceId());
        } else if (TrainingTask.RESOURCE_CASE.equals(req.getResourceType())) {
            companyScopeService.assertCaseReadable(req.getResourceId());
        } else {
            // 微练保存允许先绑未发布分身；发布时再按可用性拦（见 validateResourceAtPublish）
            companyScopeService.assertSkillOwnership(req.getResourceId());
        }
        String scenario = req.getDrillScenario();
        if (scenario == null || scenario.isBlank()) {
            throw new BusinessException(400, "微练缺少场景卡，请先生成练习内容");
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(scenario);
            if (node == null || !node.isObject()) {
                throw new BusinessException(400, "微练场景卡不是合法 JSON 对象");
            }
            com.fasterxml.jackson.databind.JsonNode questions = node.get("questions");
            if (questions == null || !questions.isArray()
                    || questions.isEmpty() || questions.size() > 3) {
                throw new BusinessException(400, "微练场景卡必须包含 1-3 个问题");
            }
            for (com.fasterxml.jackson.databind.JsonNode q : questions) {
                if (q.path("question").asText("").isBlank()) {
                    throw new BusinessException(400, "微练问题不能为空");
                }
                com.fasterxml.jackson.databind.JsonNode points = q.get("expectedPoints");
                if (points == null || !points.isArray() || points.isEmpty()) {
                    throw new BusinessException(400, "每个微练问题都必须有评分要点（expectedPoints）");
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "微练场景卡不是合法 JSON：" + e.getMessage());
        }
    }

    private void validatePrerequisitesExist(List<UUID> prerequisites, UUID planId, int version, UUID selfId) {
        if (prerequisites == null || prerequisites.isEmpty()) {
            return;
        }
        if (selfId != null && prerequisites.contains(selfId)) {
            throw new BusinessException(400, "任务不能把自己设为前置");
        }
        Set<UUID> ids = new HashSet<>();
        for (TrainingTask t : taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, version)) {
            ids.add(t.getId());
        }
        for (UUID prereq : prerequisites) {
            if (!ids.contains(prereq)) {
                throw new BusinessException(400, "前置任务不存在于当前版本：" + prereq);
            }
        }
    }

    /** 发布/预检共用的一条阻塞项；taskId 为空表示整版级问题（如环） */
    public record PublishBlocker(UUID taskId, String title, String type, String reason) {
    }

    /**
     * 收集发布阻塞项（不抛异常），发布与「检查发布」必须共用这一份逻辑。
     *
     * <p>覆盖：前置任务缺失、前置成环、知识/案例/分身不可用、题集版本无可用题目。</p>
     */
    public List<PublishBlocker> collectPublishBlockers(List<TrainingTask> tasks) {
        List<PublishBlocker> blockers = new ArrayList<>();
        Set<UUID> ids = tasks.stream().map(TrainingTask::getId).collect(Collectors.toSet());
        for (TrainingTask t : tasks) {
            for (UUID prereq : parsePrerequisites(t)) {
                if (!ids.contains(prereq)) {
                    blockers.add(new PublishBlocker(t.getId(), t.getTitle(), t.getType(),
                            "前置任务不存在，请先修复依赖"));
                }
            }
        }
        if (hasCycle(tasks)) {
            blockers.add(new PublishBlocker(null, null, null, "任务前置关系存在循环依赖"));
        }
        for (TrainingTask task : tasks) {
            String reason = resourceBlockReason(task);
            if (reason != null) {
                blockers.add(new PublishBlocker(task.getId(), task.getTitle(), task.getType(), reason));
            }
        }
        return blockers;
    }

    private String resourceBlockReason(TrainingTask task) {
        try {
            validateResourceAtPublish(task);
            return null;
        } catch (BusinessException e) {
            return e.getMessage();
        }
    }

    /** Kahn 拓扑排序查环，非递归，避免大任务集栈溢出 */
    private boolean hasCycle(List<TrainingTask> tasks) {
        Map<UUID, List<UUID>> graph = new HashMap<>();
        Map<UUID, Integer> indegree = new HashMap<>();
        for (TrainingTask t : tasks) {
            graph.putIfAbsent(t.getId(), new ArrayList<>());
            indegree.putIfAbsent(t.getId(), 0);
        }
        for (TrainingTask t : tasks) {
            for (UUID prereq : parsePrerequisites(t)) {
                if (!graph.containsKey(prereq)) {
                    continue;
                }
                graph.get(prereq).add(t.getId());
                indegree.merge(t.getId(), 1, Integer::sum);
            }
        }
        Deque<UUID> queue = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                queue.add(id);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            visited++;
            for (UUID next : graph.getOrDefault(current, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        return visited < graph.size();
    }

    /** 发布时再过一遍资源：从建任务到发布之间，资源可能被废弃或分身被删 */
    private void validateResourceAtPublish(TrainingTask task) {
        if (task.isReading()) {
            if (TrainingTask.RESOURCE_KNOWLEDGE.equals(task.getResourceType())) {
                // 发布前校验 active/未过期，避免学员打开任务时才撞 409
                companyScopeService.assertKnowledgeUsable(task.getResourceId(), task.getTitle());
            } else {
                companyScopeService.assertCaseUsable(task.getResourceId(), task.getTitle());
            }
        } else if (task.isPractice()) {
            // 保存草稿允许绑定未发布分身；发布时必须已发布，否则学员点开就是空壳
            companyScopeService.assertSkillUsable(task.getResourceId(), task.getTitle());
        } else if (task.isDrill()) {
            // 微练：分身必须已发布；知识/案例只需可读（场景卡已冻结）
            if (TrainingTask.RESOURCE_SKILL.equals(task.getResourceType())) {
                companyScopeService.assertSkillUsable(task.getResourceId(), task.getTitle());
            } else if (TrainingTask.RESOURCE_KNOWLEDGE.equals(task.getResourceType())) {
                companyScopeService.assertKnowledgeReadable(task.getResourceId());
            } else if (TrainingTask.RESOURCE_CASE.equals(task.getResourceType())) {
                companyScopeService.assertCaseReadable(task.getResourceId());
            }
        } else if (task.isExam()) {
            companyScopeService.assertDatasetReadable(task.getExamDatasetId());
            EvaluationDatasetVersion v = datasetVersionRepository
                    .findByDatasetIdAndVersion(task.getExamDatasetId(), task.getExamDatasetVersion())
                    .orElseThrow(() -> new BusinessException(400,
                            "任务「" + task.getTitle() + "」的题集版本已不存在"));
            if (!EvaluationDatasetVersion.STATUS_PUBLISHED.equals(v.getStatus())) {
                throw new BusinessException(400,
                        "任务「" + task.getTitle() + "」的题集版本已不是发布状态");
            }
            // 保存后题目可能被下线：发布时按开考口径重新数 active 题
            long available = itemRepository.countByDatasetIdAndVersionAndStatus(
                    task.getExamDatasetId(), task.getExamDatasetVersion(), EvaluationItem.STATUS_ACTIVE);
            if (available == 0) {
                throw new BusinessException(400,
                        "任务「" + task.getTitle() + "」的题集版本没有可用题目");
            }
            if (task.getExamItemCount() != null && task.getExamItemCount() > available) {
                throw new BusinessException(400,
                        "任务「" + task.getTitle() + "」的抽题数超过题集可用题数（" + available + " 题）");
            }
        }
    }

    /** pass_criteria 是否显式配置了 minPointCoverage */
    private boolean hasMinPointCoverage(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isObject() && node.hasNonNull("minPointCoverage");
        } catch (Exception e) {
            return false;
        }
    }

    private void validatePassCriteria(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new BusinessException(400, "通过条件不是合法 JSON");
        }
        if (!node.isObject()) {
            throw new BusinessException(400, "通过条件必须是 JSON 对象");
        }
        JsonNode minScore = node.get("minScore");
        if (minScore != null && (!minScore.isNumber() || minScore.asDouble() < 0 || minScore.asDouble() > 100)) {
            throw new BusinessException(400, "minScore 必须是 0-100 的数字");
        }
        JsonNode coverage = node.get("minPointCoverage");
        if (coverage != null && (!coverage.isNumber() || coverage.asDouble() < 0 || coverage.asDouble() > 1)) {
            throw new BusinessException(400, "minPointCoverage 必须是 0-1 的数字");
        }
    }

    // ==================== 辅助 ====================

    private UUID currentCompanyId() {
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            throw new BusinessException(403, "无企业归属，无权操作培训计划");
        }
        return companyId;
    }

    private TrainingPlan requirePlan(UUID planId) {
        return planRepository.findByIdAndCompanyId(planId, currentCompanyId())
                .orElseThrow(() -> new BusinessException(404, "培训计划不存在或不可访问"));
    }

    private TrainingPlanVersion requireEditableVersion(TrainingPlan plan) {
        // 退役只阻止「新分配」，但计划本身不能再改/再发布——否则一次 publish 就把退役状态复活
        if (TrainingPlan.STATUS_RETIRED.equals(plan.getStatus())) {
            throw new BusinessException(409, "计划已退役，不能修改或发布");
        }
        TrainingPlanVersion version = versionRepository
                .findByPlanIdAndVersion(plan.getId(), plan.getCurrentVersion())
                .orElseThrow(() -> new BusinessException(500, "计划版本数据缺失"));
        if (version.isPublished()) {
            throw new BusinessException(409, "当前版本已发布，请先创建新版本再编辑");
        }
        return version;
    }

    /** 考试/微练强制禁提示；其余按请求值，默认允许 */
    private boolean resolveAllowHints(TrainingTaskRequest req) {
        if (TrainingTask.TYPE_EXAM.equals(req.getType())
                || TrainingTask.TYPE_DRILL.equals(req.getType())) {
            return false;
        }
        return req.getAllowHints() == null || req.getAllowHints();
    }

    private List<UUID> nullToEmpty(List<UUID> list) {
        return list == null ? List.of() : list;
    }

    /** 解析任务前置 ID（非法 JSON 视为空，写入路径已保证格式） */
    private List<UUID> parsePrerequisites(TrainingTask task) {
        String json = task.getPrerequisites();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> raw = objectMapper.readValue(json, new TypeReference<List<String>>() { });
            List<UUID> ids = new ArrayList<>(raw.size());
            for (String s : raw) {
                ids.add(UUID.fromString(s));
            }
            return ids;
        } catch (Exception e) {
            log.warn("任务 {} 的前置任务 JSON 无法解析：{}", task.getId(), json);
            return List.of();
        }
    }

    private String writePrerequisites(List<UUID> ids) {
        try {
            List<String> raw = new ArrayList<>(ids.size());
            for (UUID id : ids) {
                raw.add(id.toString());
            }
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            throw new BusinessException(500, "前置任务序列化失败");
        }
    }
}
