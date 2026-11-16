package com.aiextract;

import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.service.TrainingProgressService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TrainingProgressService 单元测试 —— 状态迁移守卫、计数增量、解锁后继、认证推进。
 */
@ExtendWith(MockitoExtension.class)
class TrainingProgressServiceTest {

    @Mock private TrainingTaskRecordRepository recordRepository;
    @Mock private TrainingAssignmentRepository assignmentRepository;
    @Mock private TrainingCohortRepository cohortRepository;
    @Mock private TrainingTaskRepository taskRepository;
    @Mock private com.aiextract.service.TrainingNotificationService notificationService;

    private TrainingProgressService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID assignmentId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingProgressService(recordRepository, assignmentRepository,
                cohortRepository, taskRepository,
                new com.aiextract.service.TrainingRecordAggregator(), notificationService,
                objectMapper);
    }

    @Test
    void acknowledgeReading_countsUnlocksAndPromotes() throws Exception {
        UUID dependentRecordId = UUID.randomUUID();
        UUID dependentTaskId = UUID.randomUUID();

        TrainingTaskRecord record = record(recordId, taskId, TrainingTaskRecord.STATUS_IN_PROGRESS);
        TrainingTaskRecord dependent = record(dependentRecordId, dependentTaskId,
                TrainingTaskRecord.STATUS_LOCKED);
        when(recordRepository.findById(recordId)).thenReturn(Optional.of(record));
        when(recordRepository.acknowledge(eq(recordId), eq(TrainingTaskRecord.STATUS_PASSED), any()))
                .thenReturn(1);
        when(recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId))
                .thenReturn(List.of(record, dependent));

        TrainingTask task = task(taskId, true, "[]");
        // 依赖任务非必修：让 requiredTotal=1，验证「最后一个必修通过即推进」
        TrainingTask dependentTask = task(dependentTaskId, false,
                objectMapper.writeValueAsString(List.of(taskId.toString())));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(task, dependentTask));

        TrainingAssignment assignment = TrainingAssignment.builder()
                .id(assignmentId).cohortId(UUID.randomUUID())
                .status(TrainingAssignment.STATUS_IN_PROGRESS)
                .totalTasks(2).requiredTotal(1).completedTasks(0).requiredPassed(1)
                .build();
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment));
        when(cohortRepository.findById(assignment.getCohortId()))
                .thenReturn(Optional.of(TrainingCohort.builder()
                        .id(assignment.getCohortId()).planId(planId).planVersion(1).build()));

        service.acknowledgeReading(recordId);

        verify(assignmentRepository).addCounters(assignmentId, 1, 1, 1);
        verify(recordRepository).unlock(dependentRecordId);
        verify(assignmentRepository).markPendingCertification(
                assignmentId, TrainingAssignment.STATUS_PENDING_CERTIFICATION);
    }

    @Test
    void applyRecordResult_terminalRecord_isNoop() {
        when(recordRepository.findById(recordId)).thenReturn(Optional.of(
                record(recordId, taskId, TrainingTaskRecord.STATUS_PASSED)));

        service.applyRecordResult(recordId, true);

        verify(recordRepository, never()).updateStatus(any(), any(), any(), any());
        verify(assignmentRepository, never()).addCounters(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void applyRecordResult_failed_countsCompletedOnly() {
        TrainingTaskRecord record = record(recordId, taskId, TrainingTaskRecord.STATUS_IN_PROGRESS);
        when(recordRepository.findById(recordId)).thenReturn(Optional.of(record));
        when(recordRepository.updateStatus(eq(recordId), eq(TrainingTaskRecord.STATUS_IN_PROGRESS),
                eq(TrainingTaskRecord.STATUS_FAILED), any())).thenReturn(1);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task(taskId, true, "[]")));

        service.applyRecordResult(recordId, false);

        verify(assignmentRepository).addCounters(assignmentId, 1, 0, 0);
        verify(recordRepository, never()).unlock(any());
        verify(assignmentRepository, never()).markPendingCertification(any(), any());
    }

    @Test
    void applyRecordResult_retakePassedAfterFailure_countsPassedOnce() {
        UUID cohortId = UUID.randomUUID();
        TrainingTaskRecord record = record(recordId, taskId, TrainingTaskRecord.STATUS_FAILED);
        when(recordRepository.findById(recordId)).thenReturn(Optional.of(record));
        when(recordRepository.updateStatus(eq(recordId), eq(TrainingTaskRecord.STATUS_FAILED),
                eq(TrainingTaskRecord.STATUS_PASSED), any())).thenReturn(1);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task(taskId, true, "[]")));
        when(recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId))
                .thenReturn(List.of(record));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(task(taskId, true, "[]")));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder()
                        .id(assignmentId).cohortId(cohortId)
                        .status(TrainingAssignment.STATUS_IN_PROGRESS).build()));
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(
                TrainingCohort.builder().id(cohortId).planId(planId).planVersion(1).build()));

        service.applyRecordResult(recordId, true);

        // 补考通过：完成数不再 +1（首次失败已计），但通过数与必修通过数必须补上
        verify(assignmentRepository).addCounters(assignmentId, 0, 1, 1);
        verify(assignmentRepository).markPendingCertification(
                assignmentId, TrainingAssignment.STATUS_PENDING_CERTIFICATION);
    }

    @Test
    void markPendingReview_doesNotTouchCounters() {
        when(recordRepository.markPendingReview(recordId)).thenReturn(1);

        service.markPendingReview(recordId);

        verify(recordRepository).markPendingReview(recordId);
        // 结论未出，完成/通过计数必须等复核定稿后再写，否则退回会留下无法回滚的假完成
        verify(assignmentRepository, never()).addCounters(any(), anyInt(), anyInt(), anyInt());
    }

    // ==================== 辅助 ====================

    @Test
    void applyRecordResult_remediationRound_recomputesInsteadOfDelta() {
        UUID cohortId = UUID.randomUUID();
        TrainingTaskRecord round1 = TrainingTaskRecord.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId).taskId(taskId)
                .roundNo(1).status(TrainingTaskRecord.STATUS_FAILED).build();
        TrainingTaskRecord round2 = TrainingTaskRecord.builder()
                .id(recordId).assignmentId(assignmentId).taskId(taskId)
                .roundNo(2).status(TrainingTaskRecord.STATUS_IN_PROGRESS).build();
        when(recordRepository.findById(recordId)).thenReturn(Optional.of(round2));
        when(recordRepository.updateStatus(eq(recordId),
                eq(TrainingTaskRecord.STATUS_IN_PROGRESS),
                eq(TrainingTaskRecord.STATUS_PASSED), any())).thenReturn(1);
        when(assignmentRepository.findByIdForUpdate(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId).cohortId(cohortId)
                        .status(TrainingAssignment.STATUS_IN_PROGRESS)
                        .totalTasks(1).requiredTotal(1).build()));
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(
                TrainingCohort.builder().id(cohortId).planId(planId).planVersion(1).build()));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(task(taskId, true, "[]")));
        when(recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId))
                .thenReturn(List.of(round1, round2));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId).cohortId(cohortId)
                        .status(TrainingAssignment.STATUS_IN_PROGRESS).build()));

        service.applyRecordResult(recordId, true);

        // 补训轮不做 delta，按「任一通过」绝对重算：completed=1, passed=1, requiredPassed=1
        verify(assignmentRepository).setCounters(assignmentId, 1, 1, 1);
        verify(assignmentRepository, never()).addCounters(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void reevaluate_unfinishedRemediation_keepsInProgress() {
        UUID cohortId = UUID.randomUUID();
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId).cohortId(cohortId)
                        .status(TrainingAssignment.STATUS_PENDING_CERTIFICATION).build()));
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(
                TrainingCohort.builder().id(cohortId).planId(planId).planVersion(1).build()));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(task(taskId, true, "[]")));
        // 第 1 轮已通过，主管又布置了第 2 轮（尚未出结论）
        TrainingTaskRecord round1 = TrainingTaskRecord.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId).taskId(taskId)
                .roundNo(1).status(TrainingTaskRecord.STATUS_PASSED).build();
        TrainingTaskRecord round2 = TrainingTaskRecord.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId).taskId(taskId)
                .roundNo(2).status(TrainingTaskRecord.STATUS_AVAILABLE).build();
        when(recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId))
                .thenReturn(List.of(round1, round2));

        service.reevaluateCertificationState(assignmentId);

        // 必修虽已满足，但补训未完成 → 必须回到进行中，否则会出现「待认证却认证不了」
        verify(assignmentRepository).updateStatus(assignmentId,
                TrainingAssignment.STATUS_PENDING_CERTIFICATION,
                TrainingAssignment.STATUS_IN_PROGRESS);
        verify(assignmentRepository, never()).markPendingCertification(any(), any());
    }

    @Test
    void reevaluate_reopenedRoundFailed_latestRoundDecides() {
        UUID cohortId = UUID.randomUUID();
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId).cohortId(cohortId)
                        .status(TrainingAssignment.STATUS_PENDING_CERTIFICATION).build()));
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(
                TrainingCohort.builder().id(cohortId).planId(planId).planVersion(1).build()));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(task(taskId, true, "[]")));
        // 第 1 轮通过后主管判定误判要求重做（round 2），重做未通过
        TrainingTaskRecord round1 = TrainingTaskRecord.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId).taskId(taskId)
                .roundNo(1).status(TrainingTaskRecord.STATUS_PASSED).build();
        TrainingTaskRecord round2 = TrainingTaskRecord.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId).taskId(taskId)
                .roundNo(2).roundType("reopen").status(TrainingTaskRecord.STATUS_FAILED).build();
        when(recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId))
                .thenReturn(List.of(round1, round2));

        service.reevaluateCertificationState(assignmentId);

        // 最新一轮失败 → 旧通过不再满足门槛，已处于待认证的要退回进行中
        verify(assignmentRepository, never()).markPendingCertification(any(), any());
        verify(assignmentRepository).updateStatus(assignmentId,
                TrainingAssignment.STATUS_PENDING_CERTIFICATION,
                TrainingAssignment.STATUS_IN_PROGRESS);
    }

    @Test
    void reevaluate_remediationPassed_promotesToPendingCertification() {
        UUID cohortId = UUID.randomUUID();
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId).cohortId(cohortId)
                        .status(TrainingAssignment.STATUS_IN_PROGRESS).build()));
        when(cohortRepository.findById(cohortId)).thenReturn(Optional.of(
                TrainingCohort.builder().id(cohortId).planId(planId).planVersion(1).build()));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(task(taskId, true, "[]")));
        TrainingTaskRecord round1 = TrainingTaskRecord.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId).taskId(taskId)
                .roundNo(1).status(TrainingTaskRecord.STATUS_FAILED).build();
        TrainingTaskRecord round2 = TrainingTaskRecord.builder()
                .id(UUID.randomUUID()).assignmentId(assignmentId).taskId(taskId)
                .roundNo(2).status(TrainingTaskRecord.STATUS_PASSED).build();
        when(recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId))
                .thenReturn(List.of(round1, round2));
        when(assignmentRepository.markPendingCertification(
                assignmentId, TrainingAssignment.STATUS_PENDING_CERTIFICATION)).thenReturn(1);

        service.reevaluateCertificationState(assignmentId);

        verify(assignmentRepository).markPendingCertification(
                assignmentId, TrainingAssignment.STATUS_PENDING_CERTIFICATION);
        // 进入待认证时同时通知主管与学员
        verify(notificationService).notifyReviewerByCohortId(eq(cohortId), any(), any(), any(), any(), any());
    }

    private TrainingTaskRecord record(UUID id, UUID task, String status) {
        return TrainingTaskRecord.builder()
                .id(id).assignmentId(assignmentId).taskId(task).status(status).build();
    }

    private TrainingTask task(UUID id, boolean required, String prerequisites) {
        return TrainingTask.builder()
                .id(id).planId(planId).version(1).phaseNo(1).orderNo(1)
                .type(TrainingTask.TYPE_READING).title("任务").isRequired(required)
                .prerequisites(prerequisites).build();
    }
}
