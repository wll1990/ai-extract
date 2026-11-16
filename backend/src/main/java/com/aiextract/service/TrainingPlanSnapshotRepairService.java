package com.aiextract.service;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingPlan;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.TrainingPlanRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 培训内容快照修复 —— 已发布版本只读，快照缺失/损坏时不能靠改计划修。
 *
 * <p>仅重建阅读任务（knowledge/case）；微练场景是 AI 生成并冻结的，
 * 陪练/考试不使用快照，均跳过并说明原因。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingPlanSnapshotRepairService {

    private final TrainingPlanRepository planRepository;
    private final TrainingTaskRepository taskRepository;
    private final TrainingContentSnapshotService snapshotService;
    private final TrainingAccessService accessService;
    private final TrainingAuditService auditService;
    private final ObjectMapper objectMapper;

    public record TaskRepair(UUID taskId, String title, String status, String reason) {
    }

    public record RepairResult(UUID planId, int version, int repaired, int skipped, int failed,
                               List<TaskRepair> tasks) {
    }

    @Transactional(rollbackFor = Exception.class)
    public RepairResult repair(UUID planId, int version) {
        UUID companyId = accessService.requireCompanyId();
        TrainingPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new BusinessException(404, "培训计划不存在或不可访问"));
        if (!companyId.equals(plan.getCompanyId())) {
            throw new BusinessException(404, "培训计划不存在或不可访问");
        }
        List<TrainingTask> tasks = taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(
                planId, version);
        if (tasks.isEmpty()) {
            throw new BusinessException(404, "该版本没有任务");
        }

        LocalDateTime now = LocalDateTime.now();
        List<TaskRepair> results = new ArrayList<>();
        int repaired = 0;
        int skipped = 0;
        int failed = 0;
        for (TrainingTask task : tasks) {
            if (!task.isReading() || task.getResourceId() == null) {
                String reason = task.isDrill()
                        ? "微练场景由 AI 生成并冻结，不支持自动重建"
                        : "陪练/考试不使用内容快照";
                results.add(new TaskRepair(task.getId(), task.getTitle(), "skipped", reason));
                skipped++;
                continue;
            }
            try {
                Map<String, Object> snapshot = snapshotService.build(task, plan.getCompanyId());
                if (snapshot == null) {
                    results.add(new TaskRepair(task.getId(), task.getTitle(), "failed",
                            "资源不存在或不可用"));
                    failed++;
                    continue;
                }
                task.setResourceSnapshot(objectMapper.writeValueAsString(snapshot));
                task.setSnapshotVersion((task.getSnapshotVersion() == null ? 0 : task.getSnapshotVersion()) + 1);
                task.setSnapshotAt(now);
                taskRepository.save(task);
                results.add(new TaskRepair(task.getId(), task.getTitle(), "repaired", null));
                repaired++;
            } catch (Exception e) {
                log.warn("快照重建失败 taskId={}: {}", task.getId(), e.getMessage());
                results.add(new TaskRepair(task.getId(), task.getTitle(), "failed", e.getMessage()));
                failed++;
            }
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("planId", planId.toString());
        detail.put("version", version);
        detail.put("repaired", repaired);
        detail.put("skipped", skipped);
        detail.put("failed", failed);
        auditService.record("training.snapshot.repair", "training_plan", planId, detail);
        return new RepairResult(planId, version, repaired, skipped, failed, results);
    }
}
