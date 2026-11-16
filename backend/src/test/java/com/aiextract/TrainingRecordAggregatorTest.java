package com.aiextract;

import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.service.TrainingRecordAggregator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补训/重做轮次聚合口径：有效轮 = 最大 round；通过口径「最新轮决定」。
 */
class TrainingRecordAggregatorTest {

    private final TrainingRecordAggregator aggregator = new TrainingRecordAggregator();
    private final UUID assignmentId = UUID.randomUUID();
    private final UUID taskA = UUID.randomUUID();
    private final UUID taskB = UUID.randomUUID();

    @Test
    void latestByTask_picksHighestRound() {
        TrainingTaskRecord r1 = record(taskA, 1, TrainingTaskRecord.STATUS_FAILED);
        TrainingTaskRecord r2 = record(taskA, 2, TrainingTaskRecord.STATUS_PASSED);
        TrainingTaskRecord b1 = record(taskB, 1, TrainingTaskRecord.STATUS_IN_PROGRESS);

        Map<UUID, TrainingTaskRecord> latest = aggregator.latestByTask(List.of(r1, r2, b1));

        assertEquals(r2, latest.get(taskA));
        assertEquals(b1, latest.get(taskB));
    }

    @Test
    void counter_doesNotDoubleCountSameTask() {
        List<TrainingTaskRecord> records = List.of(
                record(taskA, 1, TrainingTaskRecord.STATUS_FAILED),
                record(taskA, 2, TrainingTaskRecord.STATUS_PASSED),
                record(taskB, 1, TrainingTaskRecord.STATUS_PENDING_REVIEW));
        List<TrainingTask> tasks = List.of(task(taskA, true), task(taskB, true));

        TrainingRecordAggregator.Counters counters = aggregator.count(records, tasks);

        assertEquals(1, counters.completed(), "taskA 已有终态；taskB 待复核不算完成");
        assertEquals(1, counters.passed());
        assertEquals(1, counters.requiredPassed());
    }

    @Test
    void waivedCountsAsSatisfied() {
        List<TrainingTaskRecord> records = List.of(record(taskA, 1, TrainingTaskRecord.STATUS_WAIVED));
        List<TrainingTask> tasks = List.of(task(taskA, true));

        TrainingRecordAggregator.Counters counters = aggregator.count(records, tasks);

        assertEquals(1, counters.completed());
        assertEquals(0, counters.passed(), "pass 统计只认 passed");
        assertEquals(1, counters.requiredPassed(), "豁免视同满足必修");
    }

    @Test
    void optionalTaskPassedDoesNotRaiseRequiredPassed() {
        List<TrainingTaskRecord> records = List.of(record(taskA, 1, TrainingTaskRecord.STATUS_PASSED));
        List<TrainingTask> tasks = List.of(task(taskA, false));

        TrainingRecordAggregator.Counters counters = aggregator.count(records, tasks);

        assertEquals(1, counters.passed());
        assertEquals(0, counters.requiredPassed());
    }

    @Test
    void passedTaskIds_latestRoundDecides() {
        List<TrainingTaskRecord> records = List.of(
                record(taskA, 1, TrainingTaskRecord.STATUS_PASSED),
                record(taskA, 2, TrainingTaskRecord.STATUS_FAILED));

        assertFalse(aggregator.passedTaskIds(records).contains(taskA),
                "最新一轮失败后，历史通过不能再满足门槛");
        assertEquals(0, aggregator.count(records, List.of(task(taskA, true))).requiredPassed());
    }

    @Test
    void passedTaskIds_latestRoundRemediationPassedCounts() {
        List<TrainingTaskRecord> records = List.of(
                record(taskA, 1, TrainingTaskRecord.STATUS_FAILED),
                record(taskA, 2, TrainingTaskRecord.STATUS_PASSED));

        assertTrue(aggregator.passedTaskIds(records).contains(taskA));
        assertEquals(1, aggregator.count(records, List.of(task(taskA, true))).passed());
    }

    @Test
    void reopenLatestRoundNotTerminal_clearsSatisfied() {
        List<TrainingTaskRecord> records = List.of(
                record(taskA, 1, TrainingTaskRecord.STATUS_PASSED),
                record(taskA, 2, TrainingTaskRecord.STATUS_AVAILABLE));

        assertTrue(aggregator.passedTaskIds(records).isEmpty(),
                "误判重做后，旧的 passed 不再满足认证");
        assertTrue(aggregator.terminalTaskIds(records).isEmpty());
        assertTrue(aggregator.hasUnfinishedRemediation(records));
    }

    private TrainingTaskRecord record(UUID taskId, int round, String status) {
        return TrainingTaskRecord.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId).taskId(taskId)
                .roundNo(round).status(status).build();
    }

    private TrainingTask task(UUID id, boolean required) {
        return TrainingTask.builder()
                .id(id).planId(UUID.randomUUID()).version(1)
                .type(TrainingTask.TYPE_EXAM).title("任务").isRequired(required)
                .phaseNo(1).orderNo(1).build();
    }
}
