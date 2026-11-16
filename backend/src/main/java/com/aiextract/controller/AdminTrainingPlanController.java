package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.dto.TrainingPlanRequest;
import com.aiextract.dto.TrainingTaskOrderRequest;
import com.aiextract.dto.TrainingTaskRequest;
import com.aiextract.model.TrainingPlan;
import com.aiextract.model.TrainingPlanVersion;
import com.aiextract.model.TrainingTask;
import com.aiextract.service.TrainingLearningService;
import com.aiextract.service.TrainingPlanService;
import com.aiextract.service.TrainingPlanSnapshotRepairService;
import com.aiextract.service.TrainingResourceAlertService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 培训计划管理 Controller —— 只做参数校验与路由，业务与事务在 TrainingPlanService。
 *
 * <p>全部端点挂在 {@code /admin/training/**} 下，由 SecurityConfig 的
 * {@code TRAINING_MANAGE} 规则统一保护。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/admin/training/plans")
@RequiredArgsConstructor
public class AdminTrainingPlanController {

    private final TrainingPlanService planService;
    private final TrainingLearningService learningService;
    private final TrainingResourceAlertService resourceAlertService;
    private final TrainingPlanSnapshotRepairService snapshotRepairService;
    private final com.aiextract.service.TrainingGenerationService generationService;

    // ==================== 计划 ====================

    /** 计划列表 */
    @GetMapping
    public ApiResponse<Map<String, Object>> listPlans(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<TrainingPlan> result = planService.listPlans(status, keyword, page, size);
        List<Map<String, Object>> content = result.getContent().stream()
                .map(this::toPlanMap).toList();
        return ApiResponse.success(PageResponse.of(content, result, page, size));
    }

    /** 建计划（同时创建 v1 草稿版本） */
    @PostMapping
    public ApiResponse<Map<String, Object>> createPlan(@Valid @RequestBody TrainingPlanRequest req) {
        return ApiResponse.success(toPlanMap(planService.createPlan(req)));
    }

    /**
     * AI 生成计划草稿：目标 → 候选资源 → 任务清单（含微练场景卡）。
     * 只生成 draft，仍需人工预览/调整/发布。
     */
    @PostMapping("/generate")
    public ApiResponse<Map<String, Object>> generatePlan(
            @RequestBody Map<String, Object> body) {
        String goal = body.get("goal") == null ? null : String.valueOf(body.get("goal"));
        int phaseCount = body.get("phaseCount") instanceof Number n ? n.intValue() : 4;
        var generated = generationService.generatePlan(goal, phaseCount);
        Map<String, Object> result = toPlanMap(generated.plan());
        result.put("tasks", generated.tasks().stream().map(this::toTaskMap).toList());
        result.put("note", generated.note());
        result.put("warnings", generated.warnings());
        return ApiResponse.success(result);
    }

    /** 为单个资源生成场景微练卡（不落库；前端带回到任务表单） */
    @PostMapping("/drill-preview")
    public ApiResponse<Map<String, Object>> previewDrill(@RequestBody Map<String, Object> body) {
        Object id = body.get("resourceId");
        if (id == null) {
            throw new com.aiextract.exception.BusinessException(400, "缺少资源 ID");
        }
        String resourceType = body.get("resourceType") == null
                ? com.aiextract.model.TrainingTask.RESOURCE_KNOWLEDGE
                : String.valueOf(body.get("resourceType"));
        String sceneTag = body.get("sceneTag") == null ? null : String.valueOf(body.get("sceneTag"));
        int questionCount = body.get("questionCount") instanceof Number n ? n.intValue() : 2;
        String scenario = generationService.generateDrill(
                UUID.fromString(String.valueOf(id)), resourceType, sceneTag, questionCount);
        return ApiResponse.success(Map.of("scenario", scenario));
    }

    /** 计划详情：计划 + 版本历史 + 当前版本任务树 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getPlan(@PathVariable UUID id) {
        TrainingPlanService.PlanDetail detail = planService.getPlanDetail(id);
        Map<String, Object> body = toPlanMap(detail.plan());
        body.put("versions", detail.versions().stream().map(this::toVersionMap).toList());
        // 建批次必须选已发布版本，currentVersion 可能是刚创建的 draft
        body.put("latestPublishedVersion", detail.versions().stream()
                .filter(TrainingPlanVersion::isPublished)
                .map(TrainingPlanVersion::getVersion)
                .max(Integer::compareTo).orElse(null));
        body.put("tasks", detail.tasks().stream().map(this::toTaskMap).toList());
        body.put("resourceAlerts", resourceAlertService.openAlerts(id));
        body.put("phases", toPhaseTree(detail.tasks()));
        return ApiResponse.success(body);
    }

    /** 改计划基本信息（版本号乐观锁） */
    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> updatePlan(
            @PathVariable UUID id, @Valid @RequestBody TrainingPlanRequest req) {
        return ApiResponse.success(toPlanMap(planService.updatePlan(id, req)));
    }

    /** 从已发布版本复制出新草稿版本 */
    @PostMapping("/{id}/versions")
    public ApiResponse<Map<String, Object>> createVersion(@PathVariable UUID id) {
        return ApiResponse.success(toVersionMap(planService.createVersion(id)));
    }

    /** 版本对比：新增/删除/修改的任务与字段 */
    @GetMapping("/{id}/versions/diff")
    public ApiResponse<TrainingPlanService.VersionDiff> diffVersions(
            @PathVariable UUID id, @RequestParam int from, @RequestParam int to) {
        return ApiResponse.success(planService.diffVersions(id, from, to));
    }

    // ==================== 任务 ====================

    /** 加任务（仅草稿版本） */
    @PostMapping("/{id}/tasks")
    public ApiResponse<Map<String, Object>> addTask(
            @PathVariable UUID id, @Valid @RequestBody TrainingTaskRequest req) {
        return ApiResponse.success(toTaskMap(planService.addTask(id, req)));
    }

    /** 改任务（仅草稿版本） */
    @PutMapping("/{id}/tasks/{taskId}")
    public ApiResponse<Map<String, Object>> updateTask(
            @PathVariable UUID id, @PathVariable UUID taskId,
            @Valid @RequestBody TrainingTaskRequest req) {
        return ApiResponse.success(toTaskMap(planService.updateTask(id, taskId, req)));
    }

    /** 删任务（仅草稿版本，且不能删除被引用的前置） */
    @DeleteMapping("/{id}/tasks/{taskId}")
    public ApiResponse<Void> deleteTask(@PathVariable UUID id, @PathVariable UUID taskId) {
        planService.deleteTask(id, taskId);
        return ApiResponse.success();
    }

    /** 阶段改名/重排（仅草稿；提交顺序即新顺序） */
    @PutMapping("/{id}/phases")
    public ApiResponse<Map<String, Object>> updatePhases(
            @PathVariable UUID id, @Valid @RequestBody com.aiextract.dto.TrainingPhaseRequest req) {
        List<TrainingTask> tasks = planService.updatePhases(id, req.getPhases());
        return ApiResponse.success(Map.of("tasks", tasks.stream().map(this::toTaskMap).toList()));
    }

    /** 调整顺序（必须提交完整任务 ID 集合） */
    @PutMapping("/{id}/task-order")
    public ApiResponse<Void> reorderTasks(
            @PathVariable UUID id, @Valid @RequestBody TrainingTaskOrderRequest req) {
        planService.reorderTasks(id, req);
        return ApiResponse.success();
    }

    /** 发布预检：列出全部阻塞项，发布按钮先用它「检查发布」 */
    @GetMapping("/{id}/readiness")
    public ApiResponse<Map<String, Object>> readiness(@PathVariable UUID id) {
        List<TrainingPlanService.PublishBlocker> blockers = planService.readiness(id);
        List<Map<String, Object>> items = blockers.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", b.taskId() != null ? b.taskId().toString() : null);
            m.put("title", b.title());
            m.put("type", b.type());
            m.put("reason", b.reason());
            return m;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", items.isEmpty());
        body.put("blockers", items);
        return ApiResponse.success(body);
    }

    /**
     * 学员视角预览：默认最新已发布版本，可传 ?version= 查看指定版本。
     * 只读，不创建任何学习记录。
     */
    @GetMapping("/{id}/preview")
    public ApiResponse<Map<String, Object>> preview(@PathVariable UUID id,
            @RequestParam(required = false) Integer version) {
        TrainingPlanService.PlanDetail detail = planService.getPlanPreview(id, version);
        int previewVersion = detail.tasks().isEmpty()
                ? (version != null ? version : detail.plan().getCurrentVersion())
                : detail.tasks().get(0).getVersion();
        Integer latestPublished = detail.versions().stream()
                .filter(TrainingPlanVersion::isPublished)
                .map(TrainingPlanVersion::getVersion)
                .max(Integer::compareTo).orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planId", detail.plan().getId().toString());
        body.put("name", detail.plan().getName());
        body.put("version", previewVersion);
        body.put("latestPublishedVersion", latestPublished);
        body.put("published", latestPublished != null && latestPublished == previewVersion);
        body.put("phases", toPreviewPhaseTree(detail.tasks()));
        return ApiResponse.success(body);
    }

    /** 单个任务预览：任务配置 + 绑定资源正文/字段 */
    @GetMapping("/{id}/tasks/{taskId}/preview")
    public ApiResponse<Map<String, Object>> previewTask(@PathVariable UUID id,
            @PathVariable UUID taskId) {
        TrainingPlanService.PlanDetail detail = planService.getPlanDetail(id);
        TrainingTask task = detail.tasks().stream()
                .filter(t -> t.getId().equals(taskId))
                .findFirst()
                .orElseThrow(() -> new com.aiextract.exception.BusinessException(404, "任务不存在"));
        Map<String, Object> body = toTaskMap(task);
        body.put("resource", learningService.previewResource(task));
        body.put("prerequisiteTitles", prerequisiteTitles(task, detail.tasks()));
        return ApiResponse.success(body);
    }

    /** 资源预览（任务尚未保存时使用）：只校验企业归属，不落库 */
    @GetMapping("/{id}/resource-preview")
    public ApiResponse<Map<String, Object>> previewResource(@PathVariable UUID id,
            @RequestParam String resourceType, @RequestParam UUID resourceId) {
        planService.getPlanDetail(id); // 触发计划归属校验
        String taskType = switch (resourceType) {
            case TrainingTask.RESOURCE_SKILL -> TrainingTask.TYPE_PRACTICE;
            default -> TrainingTask.TYPE_READING;
        };
        TrainingTask probe = TrainingTask.builder()
                .type(taskType)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .build();
        return ApiResponse.success(learningService.previewResource(probe));
    }

    // ==================== 发布 / 退役 ====================

    /** 发布当前草稿版本（冻结任务集） */
    @PostMapping("/{id}/publish")
    public ApiResponse<Map<String, Object>> publish(@PathVariable UUID id) {
        TrainingPlan plan = planService.publish(id);
        Map<String, Object> body = toPlanMap(plan);
        body.put("publishedVersion", plan.getCurrentVersion());
        return ApiResponse.success(body);
    }

    /** 退役（阻止新分配，不终止已有学习） */
    /** 已发布版本快照修复：重建阅读任务冻结正文（微练/陪练/考试跳过） */
    @PostMapping("/{id}/versions/{version}/snapshot-repair")
    public ApiResponse<TrainingPlanSnapshotRepairService.RepairResult> repairSnapshots(
            @PathVariable UUID id, @PathVariable int version) {
        return ApiResponse.success(snapshotRepairService.repair(id, version));
    }

    @PostMapping("/{id}/retire")
    public ApiResponse<Map<String, Object>> retire(@PathVariable UUID id) {
        return ApiResponse.success(toPlanMap(planService.retire(id)));
    }

    // ==================== 映射 ====================

    private Map<String, Object> toPlanMap(TrainingPlan plan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", plan.getId().toString());
        m.put("companyId", plan.getCompanyId() != null ? plan.getCompanyId().toString() : null);
        m.put("name", plan.getName());
        m.put("description", plan.getDescription());
        m.put("goal", plan.getGoal());
        m.put("generatedByAi", plan.getGeneratedByAi());
        m.put("generationNote", plan.getGenerationNote());
        m.put("status", plan.getStatus());
        m.put("currentVersion", plan.getCurrentVersion());
        m.put("createdBy", plan.getCreatedBy() != null ? plan.getCreatedBy().toString() : null);
        m.put("createdAt", plan.getCreatedAt());
        m.put("updatedAt", plan.getUpdatedAt());
        return m;
    }

    private Map<String, Object> toVersionMap(TrainingPlanVersion version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", version.getId().toString());
        m.put("planId", version.getPlanId().toString());
        m.put("version", version.getVersion());
        m.put("status", version.getStatus());
        m.put("taskCount", version.getTaskCount());
        m.put("publishedAt", version.getPublishedAt());
        m.put("createdAt", version.getCreatedAt());
        return m;
    }

    private Map<String, Object> toTaskMap(TrainingTask task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", task.getId().toString());
        m.put("planId", task.getPlanId().toString());
        m.put("version", task.getVersion());
        m.put("phaseNo", task.getPhaseNo());
        m.put("phaseName", task.getPhaseName());
        m.put("type", task.getType());
        m.put("title", task.getTitle());
        m.put("description", task.getDescription());
        m.put("isRequired", task.getIsRequired());
        m.put("orderNo", task.getOrderNo());
        m.put("prerequisites", task.getPrerequisites());
        m.put("resourceType", task.getResourceType());
        m.put("resourceId", task.getResourceId() != null ? task.getResourceId().toString() : null);
        m.put("sceneTag", task.getSceneTag());
        m.put("allowHints", task.getAllowHints());
        m.put("examDatasetId", task.getExamDatasetId() != null ? task.getExamDatasetId().toString() : null);
        m.put("examDatasetVersion", task.getExamDatasetVersion());
        m.put("examItemCount", task.getExamItemCount());
        m.put("examDurationMinutes", task.getExamDurationMinutes());
        m.put("passCriteria", parseJson(task.getPassCriteria()));
        m.put("maxAttempts", task.getMaxAttempts());
        m.put("deadlinePolicy", task.getDeadlinePolicy());
        // 微练场景卡：管理端编辑/预览需要原样带回；其他类型不下发快照正文
        m.put("drillScenario", TrainingTask.TYPE_DRILL.equals(task.getType())
                ? parseJson(task.getResourceSnapshot()) : null);
        m.put("snapshotVersion", task.getSnapshotVersion());
        m.put("createdAt", task.getCreatedAt());
        m.put("updatedAt", task.getUpdatedAt());
        return m;
    }

    /** JSONB 文本 → 对象；空值 null，损坏原样返回（与学员端/复核端同一形态） */
    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    /** 预览用阶段树：任务上附带绑定资源与前置任务名 */
    private List<Map<String, Object>> toPreviewPhaseTree(List<TrainingTask> tasks) {
        Map<Integer, List<TrainingTask>> grouped = new LinkedHashMap<>();
        for (TrainingTask task : tasks) {
            grouped.computeIfAbsent(task.getPhaseNo(), k -> new ArrayList<>()).add(task);
        }
        List<Map<String, Object>> phases = new ArrayList<>();
        for (Map.Entry<Integer, List<TrainingTask>> entry : grouped.entrySet()) {
            List<Map<String, Object>> items = entry.getValue().stream().map(t -> {
                Map<String, Object> m = toTaskMap(t);
                m.put("resource", learningService.previewResource(t));
                m.put("prerequisiteTitles", prerequisiteTitles(t, tasks));
                return m;
            }).toList();
            Map<String, Object> phase = new LinkedHashMap<>();
            phase.put("phaseNo", entry.getKey());
            phase.put("phaseName", entry.getValue().get(0).getPhaseName());
            phase.put("tasks", items);
            phases.add(phase);
        }
        return phases;
    }

    /** 前置任务 ID → 标题；已删除的前置保留短 ID，提示管理员修复 */
    private List<String> prerequisiteTitles(TrainingTask task, List<TrainingTask> all) {
        Map<UUID, String> titles = new LinkedHashMap<>();
        for (TrainingTask t : all) {
            titles.put(t.getId(), t.getTitle());
        }
        List<String> result = new ArrayList<>();
        for (UUID id : parsePrereqIds(task.getPrerequisites())) {
            result.add(titles.getOrDefault(id, "已删除任务（" + id.toString().substring(0, 8) + "）"));
        }
        return result;
    }

    private List<UUID> parsePrereqIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<UUID>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 阶段树：按 phaseNo 分组，组内保持服务端顺序 */
    private List<Map<String, Object>> toPhaseTree(List<TrainingTask> tasks) {
        Map<Integer, List<TrainingTask>> grouped = new LinkedHashMap<>();
        for (TrainingTask task : tasks) {
            grouped.computeIfAbsent(task.getPhaseNo(), k -> new ArrayList<>()).add(task);
        }
        List<Map<String, Object>> phases = new ArrayList<>();
        for (Map.Entry<Integer, List<TrainingTask>> entry : grouped.entrySet()) {
            List<TrainingTask> list = entry.getValue();
            Map<String, Object> phase = new LinkedHashMap<>();
            phase.put("phaseNo", entry.getKey());
            phase.put("phaseName", list.get(0).getPhaseName());
            phase.put("tasks", list.stream().map(this::toTaskMap).toList());
            phases.add(phase);
        }
        return phases;
    }
}
