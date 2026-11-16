package com.aiextract.service;

import com.aiextract.model.EvaluationDataset;
import com.aiextract.model.EvaluationDatasetVersion;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.model.Skill;
import com.aiextract.model.TrainingPlan;
import com.aiextract.model.TrainingPlanVersion;
import com.aiextract.model.TrainingResourceAlert;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.EvaluationDatasetRepository;
import com.aiextract.repository.EvaluationDatasetVersionRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.TrainingPlanRepository;
import com.aiextract.repository.TrainingPlanVersionRepository;
import com.aiextract.repository.TrainingResourceAlertRepository;
import com.aiextract.repository.TrainingTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 培训资源失效扫描 — 已发布计划引用的知识/案例/分身失效时写告警。
 *
 * <p>学员端在打开任务时已有 409 拦截；本服务补「管理端提前发现」：
 * 计划详情顶部展示可处理提醒，替换资源后下一次扫描自动关闭告警。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingResourceAlertService {

    private final TrainingPlanRepository planRepository;
    private final TrainingPlanVersionRepository versionRepository;
    private final TrainingCohortRepository cohortRepository;
    private final TrainingTaskRepository taskRepository;
    private final TrainingResourceAlertRepository alertRepository;
    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;
    private final SkillRepository skillRepository;
    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationDatasetVersionRepository datasetVersionRepository;
    private final EvaluationItemRepository itemRepository;

    /** 每 10 分钟扫描一次已发布计划 */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void scan() {
        List<TrainingPlan> plans = planRepository.findByStatus(TrainingPlan.STATUS_PUBLISHED);
        if (plans.isEmpty()) {
            return;
        }
        int opened = 0;
        int resolved = 0;
        for (TrainingPlan plan : plans) {
            ScanResult result = scanPlan(plan);
            opened += result.opened();
            resolved += result.resolved();
        }
        if (opened > 0 || resolved > 0) {
            log.info("培训资源扫描完成：新增告警 {} 条，自动关闭 {} 条", opened, resolved);
        }
    }

    private record ScanResult(int opened, int resolved) {}

    private ScanResult scanPlan(TrainingPlan plan) {
        // 扫批次实际冻结的版本，而不是 plan.currentVersion（后者可能已指向新草稿）
        Set<Integer> versions = new LinkedHashSet<>(
                cohortRepository.findDistinctPlanVersionsByPlanId(plan.getId()));
        if (versions.isEmpty()) {
            // 已发布但尚未建批次：回退到最新已发布版本，保证直接分配的计划也被覆盖
            versionRepository.findFirstByPlanIdAndStatusOrderByVersionDesc(
                            plan.getId(), TrainingPlanVersion.STATUS_PUBLISHED)
                    .ifPresent(v -> versions.add(v.getVersion()));
        }
        if (versions.isEmpty()) {
            return new ScanResult(0, 0);
        }
        int opened = 0;
        int resolved = 0;
        for (Integer version : versions) {
            ScanResult r = scanVersion(plan, version);
            opened += r.opened();
            resolved += r.resolved();
        }
        return new ScanResult(opened, resolved);
    }

    private ScanResult scanVersion(TrainingPlan plan, int version) {
        int opened = 0;
        int resolved = 0;
        List<TrainingTask> tasks = taskRepository
                .findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(plan.getId(), version);
        for (TrainingTask task : tasks) {
            String type;
            UUID resourceId;
            Integer resourceVersion = null;
            if (task.isExam()) {
                type = TrainingTask.RESOURCE_EXAM_DATASET;
                resourceId = task.getExamDatasetId();
                resourceVersion = task.getExamDatasetVersion();
            } else {
                type = task.getResourceType();
                resourceId = task.getResourceId();
            }
            if (resourceId == null || !isTrackedResource(type)) {
                continue;
            }
            String reason = TrainingTask.RESOURCE_EXAM_DATASET.equals(type)
                    ? examInvalidReason(resourceId, resourceVersion)
                    : invalidReason(type, resourceId);
            if (reason != null) {
                if (openAlert(plan, version, task, type, resourceId, resourceVersion, reason)) {
                    opened++;
                }
            } else if (resolveAlert(task.getId(), resourceId)) {
                resolved++;
            }
        }
        // 任务被删除/换资源后关闭旧告警；只处理当前版本，避免多版本互相误关
        for (TrainingResourceAlert alert : alertRepository
                .findByPlanIdAndStatusOrderByCreatedAtDesc(plan.getId(), TrainingResourceAlert.STATUS_OPEN)) {
            if (!Objects.equals(alert.getPlanVersion(), version)) {
                continue;
            }
            boolean stillExists = tasks.stream().anyMatch(t -> t.getId().equals(alert.getTaskId())
                    && alert.getResourceId().equals(effectiveResourceId(t)));
            if (!stillExists && resolveAlert(alert.getTaskId(), alert.getResourceId())) {
                resolved++;
            }
        }
        return new ScanResult(opened, resolved);
    }

    private boolean isTrackedResource(String type) {
        return TrainingTask.RESOURCE_KNOWLEDGE.equals(type)
                || TrainingTask.RESOURCE_CASE.equals(type)
                || TrainingTask.RESOURCE_SKILL.equals(type)
                || TrainingTask.RESOURCE_EXAM_DATASET.equals(type);
    }

    private UUID effectiveResourceId(TrainingTask task) {
        return task.isExam() ? task.getExamDatasetId() : task.getResourceId();
    }

    /** 考试题集失效原因：版本不存在/未发布/无可用题都提前告警 */
    private String examInvalidReason(UUID datasetId, Integer version) {
        EvaluationDataset ds = datasetRepository.findById(datasetId).orElse(null);
        if (ds == null) {
            return "题集已删除";
        }
        int v = version == null
                ? (ds.getCurrentVersion() == null ? 1 : ds.getCurrentVersion()) : version;
        EvaluationDatasetVersion ver = datasetVersionRepository
                .findByDatasetIdAndVersion(datasetId, v).orElse(null);
        if (ver == null) {
            return "题集版本 v" + v + " 不存在";
        }
        if (!EvaluationDatasetVersion.STATUS_PUBLISHED.equals(ver.getStatus())) {
            return "题集版本 v" + v + " 未发布";
        }
        long available = itemRepository.countByDatasetIdAndVersionAndStatus(
                datasetId, v, EvaluationItem.STATUS_ACTIVE);
        if (available == 0) {
            return "题集版本 v" + v + " 无可用题目";
        }
        return null;
    }

    /** 返回失效原因；null 表示资源仍可用 */
    private String invalidReason(String type, UUID resourceId) {
        LocalDateTime now = LocalDateTime.now();
        if (TrainingTask.RESOURCE_KNOWLEDGE.equals(type)) {
            KnowledgeEntry k = knowledgeRepository.findById(resourceId).orElse(null);
            if (k == null) return "知识已删除";
            if (!KnowledgeEntry.STATUS_ACTIVE.equals(k.getStatus())) return "知识已下线";
            if (k.getExpireAt() != null && k.getExpireAt().isBefore(now)) return "知识已过期";
            return null;
        }
        if (TrainingTask.RESOURCE_CASE.equals(type)) {
            IndustryCase c = caseRepository.findById(resourceId).orElse(null);
            if (c == null) return "案例已删除";
            if (!IndustryCase.STATUS_ACTIVE.equals(c.getStatus())) return "案例已下线";
            if (c.getExpireAt() != null && c.getExpireAt().isBefore(now)) return "案例已过期";
            return null;
        }
        Skill s = skillRepository.findById(resourceId).orElse(null);
        if (s == null) return "分身已删除";
        if (!"published".equals(s.getStatus())) return "分身未发布或已下线";
        return null;
    }

    private boolean openAlert(TrainingPlan plan, int version, TrainingTask task,
                              String type, UUID resourceId, Integer resourceVersion, String reason) {
        var existing = alertRepository.findFirstByTaskIdAndResourceIdAndStatus(
                task.getId(), resourceId, TrainingResourceAlert.STATUS_OPEN);
        if (existing.isPresent()) {
            TrainingResourceAlert alert = existing.get();
            // 题集版本变了：关闭旧版本告警，按新版本重新告警
            if (!Objects.equals(alert.getResourceVersion(), resourceVersion)) {
                alert.setStatus(TrainingResourceAlert.STATUS_RESOLVED);
                alert.setResolvedAt(LocalDateTime.now());
                alertRepository.save(alert);
            } else {
                return false;
            }
        }
        alertRepository.save(TrainingResourceAlert.builder()
                .id(UUID.randomUUID())
                .companyId(plan.getCompanyId())
                .planId(plan.getId())
                .planVersion(version)
                .taskId(task.getId())
                .resourceType(type)
                .resourceId(resourceId)
                .resourceVersion(resourceVersion)
                .reason(reason)
                .status(TrainingResourceAlert.STATUS_OPEN)
                .createdAt(LocalDateTime.now())
                .build());
        log.info("培训资源失效告警 planId={} taskId={} type={} reason={}",
                plan.getId(), task.getId(), type, reason);
        return true;
    }

    private boolean resolveAlert(UUID taskId, UUID resourceId) {
        return alertRepository
                .findFirstByTaskIdAndResourceIdAndStatus(taskId, resourceId, TrainingResourceAlert.STATUS_OPEN)
                .map(alert -> {
                    alert.setStatus(TrainingResourceAlert.STATUS_RESOLVED);
                    alert.setResolvedAt(LocalDateTime.now());
                    alertRepository.save(alert);
                    return true;
                })
                .orElse(false);
    }

    /** 计划详情展示：未处理告警（只读视图） */
    public List<Map<String, Object>> openAlerts(UUID planId) {
        List<TrainingResourceAlert> alerts = alertRepository
                .findByPlanIdAndStatusOrderByCreatedAtDesc(planId, TrainingResourceAlert.STATUS_OPEN);
        List<Map<String, Object>> result = new ArrayList<>(alerts.size());
        for (TrainingResourceAlert a : alerts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId().toString());
            m.put("taskId", a.getTaskId().toString());
            m.put("planVersion", a.getPlanVersion());
            m.put("resourceType", a.getResourceType());
            m.put("resourceId", a.getResourceId().toString());
            m.put("resourceVersion", a.getResourceVersion());
            m.put("reason", a.getReason());
            m.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
            result.add(m);
        }
        return result;
    }
}
