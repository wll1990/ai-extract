package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.model.Skill;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 学员学习服务 —— 任务详情、开始任务、阅读确认。
 *
 * <p>「能不能做」由服务端判定，不靠前端按钮：前置未通过、已过截止、分配已取消/已认证
 * 都会在服务层拒绝，并返回具体原因（前端用于展示阻塞清单）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingLearningService {

    private final TrainingAccessService accessService;
    private final TrainingTaskRecordRepository recordRepository;
    private final TrainingAttemptRepository attemptRepository;
    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingTaskRepository taskRepository;
    private final TrainingCohortRepository cohortRepository;
    private final TrainingProgressService progressService;
    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;
    private final TrainingContentSnapshotService snapshotService;
    private final SkillRepository skillRepository;
    private final CompanyScopeService companyScopeService;
    private final TrainingRecordAggregator aggregator;
    private final KnowledgeUsageService knowledgeUsageService;
    private final ObjectMapper objectMapper;

    /** 任务详情：进度行 + 任务 + 分配 + 阻塞原因 + 资源正文 */
    public record TaskDetail(TrainingTaskRecord record, TrainingTask task,
                             TrainingAssignment assignment, List<String> blockingReasons,
                             Map<String, Object> resource, boolean overdue) {
    }

    /** 任务列表项：进度行 + 任务定义 + 锁定原因 + 最近一次尝试 + 绑定资源标题 + 已占用次数 */
    public record TaskItem(TrainingTaskRecord record, TrainingTask task, List<String> blockingReasons,
                           TrainingAttempt latestAttempt, String resourceTitle, int usedAttempts) {
    }

    /** 任务清单 + 分配摘要（截止时间/计数），供任务页直接渲染 */
    public record AssignmentTasks(TrainingAssignment assignment, List<TaskItem> items) {
    }

    /**
     * 我的任务列表：按阶段与顺序返回该分配下的全部任务。
     *
     * <p>学员端必须有这个入口，否则只能靠 recordId 单查、无法发现任务。
     * 进度行与任务定义各查一次，锁定原因在内存里算，不做逐任务查询。</p>
     */
    public AssignmentTasks listAssignmentTasks(UUID assignmentId) {
        TrainingAssignment assignment = accessService.requireLearnerAssignment(assignmentId);
        List<TrainingTaskRecord> records =
                recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId);
        Map<UUID, TrainingTask> tasks = loadTasks(assignment);
        // 补训轮：每个任务只展示最新一轮，历史失败轮留在证据里
        List<TrainingTaskRecord> effective =
                new ArrayList<>(aggregator.latestByTask(records).values());
        Map<UUID, String> statusByTask = statusMap(effective);
        Map<UUID, TrainingAttempt> latestAttempts = latestAttempts(effective);
        Map<UUID, String> resourceTitles = resolveResourceTitles(tasks.values());
        Map<UUID, Integer> usedAttempts = usedAttempts(effective);

        List<TaskItem> items = new ArrayList<>(effective.size());
        for (TrainingTaskRecord record : effective) {
            TrainingTask task = tasks.get(record.getTaskId());
            if (task != null) {
                items.add(new TaskItem(record, task,
                        blockingReasons(record, task, statusByTask, tasks),
                        latestAttempts.get(record.getId()),
                        task.getResourceId() != null ? resourceTitles.get(task.getResourceId()) : null,
                        usedAttempts.getOrDefault(record.getId(), 0)));
            }
        }
        items.sort((a, b) -> {
            int byPhase = Integer.compare(a.task().getPhaseNo(), b.task().getPhaseNo());
            return byPhase != 0 ? byPhase : Integer.compare(a.task().getOrderNo(), b.task().getOrderNo());
        });
        return new AssignmentTasks(assignment, items);
    }

    /** 任务详情（含资源正文，供阅读页渲染） */
    public TaskDetail getTaskDetail(UUID recordId) {
        TrainingTaskRecord record = accessService.requireLearnerRecord(recordId);
        TaskDetail detail = buildDetail(record);
        recordReadingUsage(detail);
        return detail;
    }

    /**
     * 开始任务：锁定原因、截止时间、分配状态全部在服务端校验。
     *
     * <p>幂等：已在进行中/已终结时直接返回详情，不报错也不重置开始时间。</p>
     */
    @Transactional
    public TaskDetail startTask(UUID recordId) {
        TrainingTaskRecord record = accessService.requireLearnerRecord(recordId);
        TrainingAssignment assignment = assignmentRepository.findById(record.getAssignmentId())
                .orElseThrow(() -> new BusinessException(404, "培训分配不存在"));
        assertAssignmentOpen(assignment);
        assertNotOverdue(assignment);

        if (TrainingTaskRecord.STATUS_LOCKED.equals(record.getStatus())) {
            throw new BusinessException(403,
                    "任务尚未解锁：" + String.join("；", blockingReasonsFor(record)));
        }
        if (TrainingTaskRecord.STATUS_AVAILABLE.equals(record.getStatus())) {
            LocalDateTime now = LocalDateTime.now();
            int updated = recordRepository.markStarted(recordId,
                    TrainingTaskRecord.STATUS_AVAILABLE,
                    TrainingTaskRecord.STATUS_IN_PROGRESS,
                    now);
            if (updated > 0) {
                // @Modifying 不刷新一级缓存，必须同步内存状态，否则响应里还是旧状态
                record.setStatus(TrainingTaskRecord.STATUS_IN_PROGRESS);
                record.setStartedAt(now);
                progressService.markAssignmentStarted(assignment.getId());
                assignment.setStatus(TrainingAssignment.STATUS_IN_PROGRESS);
            }
        }
        return buildDetail(record);
    }

    /** 阅读任务「标记已读」：只证明阅读确认，不代表能力达标 */
    @Transactional
    public void acknowledge(UUID recordId) {
        TrainingTaskRecord record = accessService.requireLearnerRecord(recordId);
        TrainingTask task = taskRepository.findById(record.getTaskId())
                .orElseThrow(() -> new BusinessException(404, "培训任务不存在"));
        if (!task.isReading()) {
            throw new BusinessException(400, "只有阅读任务可以标记已读");
        }
        TrainingAssignment assignment = assignmentRepository.findById(record.getAssignmentId())
                .orElseThrow(() -> new BusinessException(404, "培训分配不存在"));
        assertAssignmentOpen(assignment);
        assertNotOverdue(assignment);
        if (TrainingTaskRecord.STATUS_LOCKED.equals(record.getStatus())) {
            throw new BusinessException(403,
                    "任务尚未解锁：" + String.join("；", blockingReasonsFor(record)));
        }
        progressService.acknowledgeReading(recordId);
    }

    // ==================== 组装 ====================

    /** 阅读任务打开即记一次消费回流（同人同任务同天去重） */
    private void recordReadingUsage(TaskDetail detail) {
        TrainingTask task = detail.task();
        if (task == null || !task.isReading() || task.getResourceId() == null) {
            return;
        }
        boolean isCase = TrainingTask.RESOURCE_CASE.equals(task.getResourceType());
        boolean isKnowledge = TrainingTask.RESOURCE_KNOWLEDGE.equals(task.getResourceType());
        if (!isCase && !isKnowledge) {
            return;
        }
        TrainingAssignment assignment = detail.assignment();
        knowledgeUsageService.logTrainingReading(assignment.getCompanyId(), assignment.getId(),
                task.getId(), assignment.getLearnerId(), detail.record().getId(),
                task.getResourceId(), isCase, null);
    }

    private TaskDetail buildDetail(TrainingTaskRecord record) {
        TrainingAssignment assignment = assignmentRepository.findById(record.getAssignmentId())
                .orElseThrow(() -> new BusinessException(404, "培训分配不存在"));
        Map<UUID, TrainingTask> tasks = loadTasks(assignment);
        TrainingTask task = tasks.get(record.getTaskId());
        if (task == null) {
            task = taskRepository.findById(record.getTaskId())
                    .orElseThrow(() -> new BusinessException(404, "培训任务不存在"));
        }
        List<TrainingTaskRecord> siblings =
                recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignment.getId());
        boolean overdue = assignment.getDeadline() != null
                && assignment.getDeadline().isBefore(LocalDateTime.now())
                && (TrainingAssignment.STATUS_NOT_STARTED.equals(assignment.getStatus())
                    || TrainingAssignment.STATUS_IN_PROGRESS.equals(assignment.getStatus()));
        List<TrainingTaskRecord> effective =
                new ArrayList<>(aggregator.latestByTask(siblings).values());
        return new TaskDetail(record, task, assignment,
                blockingReasons(record, task, statusMap(effective), tasks),
                resourceOf(task), overdue);
    }

    /** 一次查出该分配对应冻结版本的全部任务 */
    private Map<UUID, TrainingTask> loadTasks(TrainingAssignment assignment) {
        Map<UUID, TrainingTask> tasks = new HashMap<>();
        cohortRepository.findById(assignment.getCohortId()).ifPresent(cohort -> {
            for (TrainingTask task : taskRepository
                    .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(
                            cohort.getPlanId(), cohort.getPlanVersion())) {
                tasks.put(task.getId(), task);
            }
        });
        return tasks;
    }

    /** 每条进度行最近一次尝试（一次批量查询，无 N+1） */
    private Map<UUID, TrainingAttempt> latestAttempts(List<TrainingTaskRecord> records) {
        Map<UUID, TrainingAttempt> latest = new HashMap<>();
        if (records.isEmpty()) {
            return latest;
        }
        List<UUID> recordIds = records.stream().map(TrainingTaskRecord::getId).toList();
        for (TrainingAttempt attempt : attemptRepository
                .findByTaskRecordIdInOrderByAttemptNoDesc(recordIds)) {
            latest.putIfAbsent(attempt.getTaskRecordId(), attempt);
        }
        return latest;
    }

    /** 与服务端开考口径一致：只有已定稿/待复核的尝试占用次数 */
    private Map<UUID, Integer> usedAttempts(List<TrainingTaskRecord> records) {
        Map<UUID, Integer> used = new HashMap<>();
        if (records.isEmpty()) {
            return used;
        }
        List<UUID> recordIds = records.stream().map(TrainingTaskRecord::getId).toList();
        for (Object[] row : attemptRepository.countUsedByRecordIds(recordIds,
                List.of(TrainingAttempt.STATUS_FINALIZED, TrainingAttempt.STATUS_PENDING_REVIEW))) {
            used.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return used;
    }

    private Map<UUID, String> statusMap(List<TrainingTaskRecord> records) {
        Map<UUID, String> statusByTask = new HashMap<>();
        for (TrainingTaskRecord r : records) {
            statusByTask.put(r.getTaskId(), r.getStatus());
        }
        return statusByTask;
    }

    /** 只对锁定任务给出具体原因：是哪几个前置没过（纯内存计算，无额外查询） */
    private List<String> blockingReasons(TrainingTaskRecord record, TrainingTask task,
                                         Map<UUID, String> statusByTask,
                                         Map<UUID, TrainingTask> tasks) {
        if (!TrainingTaskRecord.STATUS_LOCKED.equals(record.getStatus()) || task == null) {
            return List.of();
        }
        List<UUID> prereqs = parsePrerequisites(task);
        if (prereqs.isEmpty()) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        for (UUID prereq : prereqs) {
            if (!TrainingTaskRecord.STATUS_PASSED.equals(statusByTask.get(prereq))) {
                TrainingTask prereqTask = tasks.get(prereq);
                String title = prereqTask != null ? prereqTask.getTitle() : prereq.toString();
                reasons.add("先完成任务「" + title + "」");
            }
        }
        return reasons;
    }

    /** 锁定任务的阻断原因（开始/已读前校验用） */
    private List<String> blockingReasonsFor(TrainingTaskRecord record) {
        TrainingAssignment assignment = assignmentRepository.findById(record.getAssignmentId())
                .orElseThrow(() -> new BusinessException(404, "培训分配不存在"));
        Map<UUID, TrainingTask> tasks = loadTasks(assignment);
        TrainingTask task = tasks.get(record.getTaskId());
        if (task == null) {
            // 批次/版本数据缺失时仍要给出可读原因，不能静默返回空列表
            task = taskRepository.findById(record.getTaskId()).orElse(null);
        }
        List<TrainingTaskRecord> siblings =
                recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignment.getId());
        List<TrainingTaskRecord> effective =
                new ArrayList<>(aggregator.latestByTask(siblings).values());
        return blockingReasons(record, task, statusMap(effective), tasks);
    }

    /** 阅读任务的资源正文；陪练任务返回分身信息；考试不在这里下发题面 */
    private Map<String, Object> resourceOf(TrainingTask task) {
        if (task.isDrill()) {
            // 微练只下发场景与问题；评分要点（expectedPoints）绝不能给学员
            Map<String, Object> snapshot = snapshotService.read(task);
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("type", "drill");
            if (snapshot == null) {
                resource.put("available", false);
                return resource;
            }
            resource.put("title", task.getTitle());
            resource.put("scenario", snapshot.get("scenario"));
            resource.put("clientRole", snapshot.get("clientRole"));
            resource.put("sceneTag", task.getSceneTag());
            Object questions = snapshot.get("questions");
            if (questions instanceof List<?> list) {
                List<Map<String, Object>> safe = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof Map<?, ?> q) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("question", q.get("question"));
                        row.put("context", q.get("context"));
                        safe.add(row);
                    }
                }
                resource.put("questions", safe);
            }
            return resource;
        }
        // 阅读任务优先用发布时冻结的快照，资源下线/修改不影响已分配学员
        Map<String, Object> snapshot = snapshotService.read(task);
        if (snapshot != null && task.isReading()) {
            // 快照也必须做企业校验：跨企业资源绝不能因为快照而绕过隔离
            if (snapshot.containsKey("companyId")) {
                Object cid = snapshot.get("companyId");
                if (cid != null) {
                    ensureReadableCompany(UUID.fromString(cid.toString()));
                }
            } else {
                ensureReadableCompany(resolveResourceCompanyOrThrow(task));
            }
            snapshot.put("snapshot", true);
            return snapshot;
        }
        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("type", task.getResourceType());
        if (task.getResourceId() == null) {
            return resource;
        }
        resource.put("id", task.getResourceId().toString());
        if (task.isReading() && TrainingTask.RESOURCE_KNOWLEDGE.equals(task.getResourceType())) {
            KnowledgeEntry entry = knowledgeRepository.findById(task.getResourceId()).orElse(null);
            if (entry == null) {
                throw new BusinessException(409, "该知识已被删除，请联系管理员更新后重新下发");
            }
            {
                ensureReadableCompany(entry.getCompanyId());
                if (!"active".equals(entry.getStatus())
                        || (entry.getExpireAt() != null && entry.getExpireAt().isBefore(java.time.LocalDateTime.now()))) {
                    throw new BusinessException(409, "该知识已过期或已下线，请联系管理员更新后重新下发");
                }
                resource.put("title", entry.getTitle());
                resource.put("content", entry.getContent());
            }
        } else if (task.isReading() && TrainingTask.RESOURCE_CASE.equals(task.getResourceType())) {
            IndustryCase item = caseRepository.findById(task.getResourceId()).orElse(null);
            if (item == null) {
                throw new BusinessException(409, "该案例已被删除，请联系管理员更新后重新下发");
            }
            {
                ensureReadableCompany(item.getCompanyId());
                if (!"active".equals(item.getStatus())
                        || (item.getExpireAt() != null && item.getExpireAt().isBefore(java.time.LocalDateTime.now()))) {
                    throw new BusinessException(409, "该案例已过期或已下线，请联系管理员更新后重新下发");
                }
                resource.put("title", item.getTitle());
                resource.put("customerProfile", item.getCustomerProfile());
                resource.put("funnelStage", item.getFunnelStage());
                resource.put("sceneTag", item.getSceneTag());
                resource.put("dialogueQuote", item.getDialogueQuote());
                resource.put("objection", item.getObjection());
                resource.put("emotionChange", item.getEmotionChange());
                resource.put("keyActions", item.getKeyActions());
                resource.put("scripts", item.getScripts());
                resource.put("advanceNodes", item.getAdvanceNodes());
                resource.put("result", item.getResult());
                resource.put("reusablePoints", item.getReusablePoints());
                resource.put("failurePoints", item.getFailurePoints());
            }
        } else if (task.isPractice()) {
            Skill skill = skillRepository.findById(task.getResourceId()).orElse(null);
            if (skill != null) {
                ensureReadableCompany(companyScopeService.resolveSkillCompanyId(skill));
                resource.put("title", skill.getDisplayName());
                resource.put("openingMessage", skill.getOpeningMessage());
            }
        }
        return resource;
    }

    /** 历史快照没有 companyId 时，按当前资源归属企业做隔离校验；资源不存在则拒绝。 */
    private UUID resolveResourceCompanyOrThrow(TrainingTask task) {
        if (TrainingTask.RESOURCE_KNOWLEDGE.equals(task.getResourceType())) {
            return knowledgeRepository.findById(task.getResourceId())
                    .map(KnowledgeEntry::getCompanyId)
                    .orElseThrow(() -> new BusinessException(404, "培训资源不存在或不可访问"));
        }
        if (TrainingTask.RESOURCE_CASE.equals(task.getResourceType())) {
            return caseRepository.findById(task.getResourceId())
                    .map(IndustryCase::getCompanyId)
                    .orElseThrow(() -> new BusinessException(404, "培训资源不存在或不可访问"));
        }
        return null;
    }

    /**
     * 管理端任务预览：复用学员端资源装配，但资源不可用时返回原因而不是 409。
     *
     * <p>草稿允许先绑资源后修复；管理员需要看到「绑的是什么、为什么不可用」，
     * 而不是整页报错。</p>
     */
    public Map<String, Object> previewResource(TrainingTask task) {
        try {
            Map<String, Object> resource = resourceOf(task);
            resource.put("available", true);
            return resource;
        } catch (BusinessException e) {
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("type", task.getResourceType());
            resource.put("id", task.getResourceId() != null ? task.getResourceId().toString() : null);
            resource.put("available", false);
            resource.put("unavailableReason", e.getMessage());
            return resource;
        }
    }

    /** 批量解析任务绑定资源标题，避免逐任务查询 */
    private Map<UUID, String> resolveResourceTitles(java.util.Collection<TrainingTask> tasks) {
        java.util.Set<UUID> knowledgeIds = new java.util.HashSet<>();
        java.util.Set<UUID> caseIds = new java.util.HashSet<>();
        java.util.Set<UUID> skillIds = new java.util.HashSet<>();
        for (TrainingTask task : tasks) {
            if (task.getResourceId() == null) {
                continue;
            }
            if (TrainingTask.RESOURCE_KNOWLEDGE.equals(task.getResourceType())) {
                knowledgeIds.add(task.getResourceId());
            } else if (TrainingTask.RESOURCE_CASE.equals(task.getResourceType())) {
                caseIds.add(task.getResourceId());
            } else if (TrainingTask.RESOURCE_SKILL.equals(task.getResourceType())) {
                skillIds.add(task.getResourceId());
            }
        }
        Map<UUID, String> titles = new HashMap<>();
        UUID companyId = TokenContext.getCompanyId();
        boolean unrestricted = companyScopeService.isUnrestrictedDataScope();
        if (!knowledgeIds.isEmpty()) {
            (unrestricted
                    ? knowledgeRepository.findAllById(knowledgeIds)
                    : knowledgeRepository.findVisibleByIdIn(knowledgeIds, companyId))
                    .forEach(k -> titles.put(k.getId(), k.getTitle()));
        }
        if (!caseIds.isEmpty()) {
            (unrestricted
                    ? caseRepository.findAllById(caseIds)
                    : caseRepository.findVisibleByIdIn(caseIds, companyId))
                    .forEach(c -> titles.put(c.getId(), c.getTitle()));
        }
        if (!skillIds.isEmpty()) {
            skillRepository.findAllById(skillIds).forEach(s -> {
                UUID owner = companyScopeService.resolveSkillCompanyId(s);
                if (unrestricted || owner == null || owner.equals(companyId)) {
                    titles.put(s.getId(), s.getDisplayName());
                }
            });
        }
        return titles;
    }

    /** 公共资源（company_id 为空）允许读取；super_admin 可读全部 */
    private void ensureReadableCompany(UUID resourceCompanyId) {
        if (companyScopeService.isUnrestrictedDataScope()) {
            return;
        }
        UUID companyId = TokenContext.getCompanyId();
        if (resourceCompanyId != null && !resourceCompanyId.equals(companyId)) {
            throw new BusinessException(404, "培训资源不存在或不可访问");
        }
    }

    private void assertAssignmentOpen(TrainingAssignment assignment) {
        if (!assignment.isOpen()) {
            throw new BusinessException(409, "该培训已结束，不能再操作");
        }
    }

    private void assertNotOverdue(TrainingAssignment assignment) {
        if (assignment.getDeadline() != null
                && assignment.getDeadline().isBefore(LocalDateTime.now())) {
            throw new BusinessException(403, "已超过截止时间，不能再开始或提交");
        }
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
}
