package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.service.TrainingAccessService;
import com.aiextract.service.TrainingContentSnapshotService;
import com.aiextract.service.KnowledgeUsageService;
import com.aiextract.service.TrainingLearningService;
import com.aiextract.service.TrainingProgressService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TrainingLearningService 单元测试 —— 开始任务的可见状态、锁定/逾期阻断、类型校验。
 */
@ExtendWith(MockitoExtension.class)
class TrainingLearningServiceTest {

    @Mock private TrainingAccessService accessService;
    @Mock private TrainingTaskRecordRepository recordRepository;
    @Mock private com.aiextract.repository.TrainingAttemptRepository attemptRepository;
    @Mock private TrainingAssignmentRepository assignmentRepository;
    @Mock private TrainingTaskRepository taskRepository;
    @Mock private TrainingCohortRepository cohortRepository;
    @Mock private TrainingProgressService progressService;
    @Mock private KnowledgeEntryRepository knowledgeRepository;
    @Mock private IndustryCaseRepository caseRepository;
    @Mock private TrainingContentSnapshotService snapshotService;
    @Mock private SkillRepository skillRepository;
    @Mock private CompanyScopeService companyScopeService;
    @Mock private KnowledgeUsageService knowledgeUsageService;

    private TrainingLearningService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingLearningService(accessService, recordRepository, attemptRepository,
                assignmentRepository, taskRepository, cohortRepository, progressService,
                knowledgeRepository, caseRepository, snapshotService, skillRepository, companyScopeService,
                new com.aiextract.service.TrainingRecordAggregator(), knowledgeUsageService, objectMapper);
        TokenContext.set(userId, companyId);
        lenient().when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(assignment(null)));
        lenient().when(attemptRepository.countUsedByRecordIds(any(), any())).thenReturn(java.util.List.of());
        // 默认没有快照，走实时资源读取；快照路径由专项用例覆盖
        lenient().when(snapshotService.read(org.mockito.ArgumentMatchers.any())).thenReturn(null);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void startTask_available_returnsInProgressImmediately() {
        TrainingTaskRecord record = record(TrainingTaskRecord.STATUS_AVAILABLE);
        when(accessService.requireLearnerRecord(recordId)).thenReturn(record);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(readingTask()));
        when(knowledgeRepository.findById(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.of(
                KnowledgeEntry.builder().id(UUID.randomUUID()).companyId(companyId)
                        .status("active").title("阅读资料").content("正文").build()));
        when(recordRepository.markStarted(eq(recordId), anyString(), anyString(), any())).thenReturn(1);

        TrainingLearningService.TaskDetail detail = service.startTask(recordId);

        assertEquals(TrainingTaskRecord.STATUS_IN_PROGRESS, detail.record().getStatus(),
                "响应必须立即反映新状态，不能因为一级缓存返回旧值");
        assertEquals(TrainingAssignment.STATUS_IN_PROGRESS, detail.assignment().getStatus());
        verify(progressService).markAssignmentStarted(assignmentId);
    }

    @Test
    void startTask_locked_blocksWithReason() {
        TrainingTaskRecord record = record(TrainingTaskRecord.STATUS_LOCKED);
        when(accessService.requireLearnerRecord(recordId)).thenReturn(record);
        TrainingTask task = readingTask();
        task.setPrerequisites(objectMapper.valueToTree(List.of(UUID.randomUUID().toString())).toString());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(recordRepository.findByAssignmentIdOrderByCreatedAtAsc(assignmentId))
                .thenReturn(List.of(record));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.startTask(recordId));

        assertEquals(403, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("尚未解锁"));
    }

    @Test
    void startTask_overdue_blocks() {
        when(accessService.requireLearnerRecord(recordId)).thenReturn(
                record(TrainingTaskRecord.STATUS_AVAILABLE));
        // 逾期在加载任务之前就拒绝，不会调用 taskRepository
        when(assignmentRepository.findById(assignmentId))
                .thenReturn(Optional.of(assignment(LocalDateTime.now().minusDays(1))));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.startTask(recordId));

        assertEquals(403, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("截止时间"));
    }

    @Test
    void acknowledge_onPracticeTask_rejected() {
        when(accessService.requireLearnerRecord(recordId)).thenReturn(
                record(TrainingTaskRecord.STATUS_IN_PROGRESS));
        TrainingTask practice = readingTask();
        practice.setType(TrainingTask.TYPE_PRACTICE);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(practice));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.acknowledge(recordId));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("阅读任务"));
    }

    // ==================== 辅助 ====================

    private TrainingTaskRecord record(String status) {
        return TrainingTaskRecord.builder()
                .id(recordId).assignmentId(assignmentId).taskId(taskId).status(status).build();
    }

    private TrainingTask readingTask() {
        return TrainingTask.builder()
                .id(taskId).planId(UUID.randomUUID()).version(1)
                .type(TrainingTask.TYPE_READING).title("阅读一")
                .phaseNo(1).orderNo(1).isRequired(true)
                .prerequisites("[]")
                .resourceType(TrainingTask.RESOURCE_KNOWLEDGE)
                .resourceId(UUID.randomUUID())
                .build();
    }

    private TrainingAssignment assignment(LocalDateTime deadline) {
        return TrainingAssignment.builder()
                .id(assignmentId).companyId(companyId).cohortId(UUID.randomUUID())
                .learnerId(userId).status(TrainingAssignment.STATUS_NOT_STARTED)
                .deadline(deadline)
                .totalTasks(2).requiredTotal(2)
                .completedTasks(0).requiredPassed(0).passedTasks(0)
                .build();
    }
}
