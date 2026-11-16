package com.aiextract.service;

import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 补训轮次聚合规则 —— 唯一口径。
 *
 * <p>一个任务可能有 round 1（通过）+ round 2（主管判定误判、要求重做）。
 * 所有面向学员/看板的「当前状态」、计数与认证判定都<b>只看最新一轮</b>：
 * 重开一轮后旧的 passed 不再满足门槛，否则「已通过」会把主管要求的重做掩盖掉；
 * 补训失败后旧的 passed 也不会让任务重新达标。历史轮次仍完整保留，用于追溯。</p>
 *
 * <p>历史成绩不会被最高分掩盖：计数只回答「这门当前是否已通过」，
 * 具体分数与每一轮的结论仍在 attempt 历史里。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Component
public class TrainingRecordAggregator {

    /** 每个任务的有效轮次（round 最大的一行） */
    public Map<UUID, TrainingTaskRecord> latestByTask(List<TrainingTaskRecord> records) {
        Map<UUID, TrainingTaskRecord> latest = new HashMap<>();
        for (TrainingTaskRecord record : records) {
            TrainingTaskRecord current = latest.get(record.getTaskId());
            if (current == null || round(record) > round(current)) {
                latest.put(record.getTaskId(), record);
            }
        }
        return latest;
    }

    /** 最新一轮为 passed/waived 的任务集合（认证与前置解锁口径） */
    public Set<UUID> passedTaskIds(List<TrainingTaskRecord> records) {
        Set<UUID> ids = new HashSet<>();
        for (TrainingTaskRecord record : latestByTask(records).values()) {
            if (TrainingTaskRecord.STATUS_PASSED.equals(record.getStatus())
                    || TrainingTaskRecord.STATUS_WAIVED.equals(record.getStatus())) {
                ids.add(record.getTaskId());
            }
        }
        return ids;
    }

    /** 最新一轮为终态（passed/failed/waived）的任务集合 */
    public Set<UUID> terminalTaskIds(List<TrainingTaskRecord> records) {
        Set<UUID> ids = new HashSet<>();
        for (TrainingTaskRecord record : latestByTask(records).values()) {
            if (record.isTerminal()) {
                ids.add(record.getTaskId());
            }
        }
        return ids;
    }

    /** 任务是否有待复核的轮次 */
    public boolean hasPendingReview(List<TrainingTaskRecord> records) {
        return records.stream().anyMatch(r -> TrainingTaskRecord.STATUS_PENDING_REVIEW.equals(r.getStatus()));
    }

    /**
     * 是否存在尚未出结论的补训轮（round&gt;1 且非终态）。
     *
     * <p>补训是为了让学员重新达标；只要还有一轮补训没结果，分配就不能停在待认证，
     * 否则「必修已全部通过」会掩盖「主管要求的整改还没做」。</p>
     */
    public boolean hasUnfinishedRemediation(List<TrainingTaskRecord> records) {
        return records.stream()
                .anyMatch(r -> round(r) > 1 && !r.isTerminal());
    }

    /**
     * 冗余计数口径（一律按每任务最新一轮）：completed=终态任务数，
     * passed=通过任务数，requiredPassed=通过/豁免的必修任务数。
     */
    public Counters count(List<TrainingTaskRecord> records, List<TrainingTask> tasks) {
        Set<UUID> taskIds = new HashSet<>();
        Set<UUID> requiredIds = new HashSet<>();
        for (TrainingTask task : tasks) {
            taskIds.add(task.getId());
            if (Boolean.TRUE.equals(task.getIsRequired())) {
                requiredIds.add(task.getId());
            }
        }
        Set<UUID> terminal = terminalTaskIds(records);
        terminal.retainAll(taskIds);
        Set<UUID> satisfied = passedTaskIds(records);
        satisfied.retainAll(taskIds);
        // passed_tasks 只统计最新一轮真正 passed；waived 计入 satisfied（认证门槛），不计入通过数
        Set<UUID> passed = new HashSet<>();
        for (TrainingTaskRecord record : latestByTask(records).values()) {
            if (TrainingTaskRecord.STATUS_PASSED.equals(record.getStatus())
                    && taskIds.contains(record.getTaskId())) {
                passed.add(record.getTaskId());
            }
        }

        int requiredPassed = 0;
        for (UUID id : satisfied) {
            if (requiredIds.contains(id)) {
                requiredPassed++;
            }
        }
        return new Counters(terminal.size(), passed.size(), requiredPassed);
    }

    private int round(TrainingTaskRecord record) {
        return record.getRoundNo() == null ? 1 : record.getRoundNo();
    }

    public record Counters(int completed, int passed, int requiredPassed) {
    }
}
