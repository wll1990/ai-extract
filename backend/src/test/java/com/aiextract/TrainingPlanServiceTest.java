package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.TrainingPlanRequest;
import com.aiextract.dto.TrainingTaskOrderRequest;
import com.aiextract.dto.TrainingTaskRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationDatasetVersion;
import com.aiextract.model.TrainingPlan;
import com.aiextract.model.TrainingPlanVersion;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.EvaluationDatasetVersionRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.TrainingPlanRepository;
import com.aiextract.repository.TrainingPlanVersionRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.service.TrainingPlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TrainingPlanService 单元测试 —— 版本编辑边界、发布校验、前置依赖与计数快照。
 */
@ExtendWith(MockitoExtension.class)
class TrainingPlanServiceTest {

    @Mock
    private TrainingPlanRepository planRepository;
    @Mock
    private TrainingPlanVersionRepository versionRepository;
    @Mock
    private TrainingTaskRepository taskRepository;
    @Mock
    private com.aiextract.service.TrainingContentSnapshotService snapshotService;
    @Mock
    private EvaluationDatasetVersionRepository datasetVersionRepository;
    @Mock
    private EvaluationItemRepository itemRepository;
    @Mock
    private CompanyScopeService companyScopeService;

    private TrainingPlanService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingPlanService(planRepository, versionRepository, taskRepository,
                snapshotService, datasetVersionRepository, itemRepository, companyScopeService, objectMapper);
        TokenContext.set(userId, companyId);
        lenient().when(planRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(versionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(taskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(taskRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    // ==================== 计划 ====================

    @Test
    void createPlan_createsDraftVersionOne() {
        TrainingPlan plan = service.createPlan(TrainingPlanRequest.builder().name("新人 30 天").build());

        assertEquals(TrainingPlan.STATUS_DRAFT, plan.getStatus());
        assertEquals(1, plan.getCurrentVersion());
        assertEquals(companyId, plan.getCompanyId());

        ArgumentCaptor<TrainingPlanVersion> captor = ArgumentCaptor.forClass(TrainingPlanVersion.class);
        verify(versionRepository).save(captor.capture());
        assertEquals(1, captor.getValue().getVersion());
        assertEquals(TrainingPlanVersion.STATUS_DRAFT, captor.getValue().getStatus());
        assertEquals(plan.getId(), captor.getValue().getPlanId());
    }

    @Test
    void createPlan_withoutCompany_isForbidden() {
        TokenContext.clear();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createPlan(TrainingPlanRequest.builder().name("x").build()));

        assertEquals(403, ex.getErrorCode());
    }

    @Test
    void updatePlan_staleVersion_conflicts() {
        when(planRepository.findByIdAndCompanyId(planId, companyId))
                .thenReturn(Optional.of(plan(TrainingPlan.STATUS_DRAFT, 3)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updatePlan(planId, TrainingPlanRequest.builder()
                        .name("改名").expectedVersion(2).build()));

        assertEquals(409, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("刷新"));
    }

    // ==================== 任务编辑边界 ====================

    @Test
    void addTask_publishedVersion_isReadOnly() {
        when(planRepository.findByIdAndCompanyId(planId, companyId))
                .thenReturn(Optional.of(plan(TrainingPlan.STATUS_PUBLISHED, 1)));
        when(versionRepository.findByPlanIdAndVersion(planId, 1))
                .thenReturn(Optional.of(version(TrainingPlanVersion.STATUS_PUBLISHED)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addTask(planId, readingTask()));

        assertEquals(409, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("创建新版本"));
        verify(taskRepository, never()).save(any());
    }

    @Test
    void addTask_examWithoutDuration_rejected() {
        stubDraftVersion();
        TrainingTaskRequest req = examTask();
        req.setExamDurationMinutes(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addTask(planId, req));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("考试时长"));
    }

    @Test
    void addTask_examForcesHintsOff() {
        stubDraftVersion();
        TrainingTaskRequest req = examTask();
        req.setAllowHints(true);

        TrainingTask task = service.addTask(planId, req);

        assertFalse(task.getAllowHints(), "考试任务必须禁提示，前端传 true 也要被服务端改掉");
        assertEquals(60, task.getExamDurationMinutes());
    }

    @Test
    void addTask_readingWithSkillResource_rejected() {
        stubDraftVersion();
        TrainingTaskRequest req = readingTask();
        req.setResourceType(TrainingTask.RESOURCE_SKILL);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addTask(planId, req));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("知识或案例"));
    }

    @Test
    void addTask_drillWithoutScenario_rejected() {
        stubDraftVersion();
        TrainingTaskRequest req = drillTask();
        req.setDrillScenario(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addTask(planId, req));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("场景卡"));
    }

    @Test
    void addTask_drillQuestionWithoutPoints_rejected() {
        stubDraftVersion();
        TrainingTaskRequest req = drillTask();
        req.setDrillScenario("{\"scenario\":\"客户嫌贵\",\"questions\":[{\"question\":\"怎么回应？\"}]}");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addTask(planId, req));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("评分要点"));
    }

    @Test
    void addTask_drillPersistsScenarioAndForcesHintsOff() {
        stubDraftVersion();
        TrainingTaskRequest req = drillTask();
        req.setAllowHints(true);

        TrainingTask task = service.addTask(planId, req);

        assertTrue(task.isDrill());
        assertEquals(req.getDrillScenario(), task.getResourceSnapshot(), "场景卡随任务冻结");
        assertEquals(1, task.getSnapshotVersion());
        assertFalse(task.getAllowHints(), "微练不允许提示，避免提前看到评分要点");
    }

    @Test
    void addTask_prerequisiteOutsideVersion_rejected() {
        stubDraftVersion();
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of());
        TrainingTaskRequest req = readingTask();
        req.setPrerequisites(List.of(UUID.randomUUID()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addTask(planId, req));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("前置任务不存在"));
    }

    @Test
    void deleteTask_referencedAsPrerequisite_blocked() throws Exception {
        stubDraftVersion();
        UUID targetId = UUID.randomUUID();
        TrainingTask target = task(targetId, "阅读一", "[]");
        TrainingTask sibling = task(UUID.randomUUID(), "考试一",
                objectMapper.writeValueAsString(List.of(targetId.toString())));
        when(taskRepository.findByIdAndPlanIdAndVersion(targetId, planId, 1))
                .thenReturn(Optional.of(target));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(target, sibling));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteTask(planId, targetId));

        assertEquals(409, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("前置"));
        verify(taskRepository, never()).delete(any());
    }

    @Test
    void reorderTasks_incompleteIdSet_conflicts() {
        stubDraftVersion();
        TrainingTask a = task(UUID.randomUUID(), "A", "[]");
        TrainingTask b = task(UUID.randomUUID(), "B", "[]");
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(a, b));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reorderTasks(planId,
                        TrainingTaskOrderRequest.builder().taskIds(List.of(a.getId())).build()));

        assertEquals(409, ex.getErrorCode());
    }

    // ==================== 发布 ====================

    @Test
    void publish_emptyPlan_rejected() {
        stubDraftVersion();
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.publish(planId));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("至少需要一个任务"));
    }

    @Test
    void publish_cycleInPrerequisites_rejected() throws Exception {
        stubDraftVersion();
        UUID aId = UUID.randomUUID();
        UUID bId = UUID.randomUUID();
        TrainingTask a = task(aId, "A", objectMapper.writeValueAsString(List.of(bId.toString())));
        TrainingTask b = task(bId, "B", objectMapper.writeValueAsString(List.of(aId.toString())));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(a, b));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.publish(planId));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("循环依赖"));
    }

    @Test
    void publish_freezesVersionAndCountsTasks() {
        TrainingPlan plan = plan(TrainingPlan.STATUS_DRAFT, 1);
        TrainingPlanVersion draft = version(TrainingPlanVersion.STATUS_DRAFT);
        when(planRepository.findByIdAndCompanyId(planId, companyId)).thenReturn(Optional.of(plan));
        when(versionRepository.findByPlanIdAndVersion(planId, 1)).thenReturn(Optional.of(draft));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(task(UUID.randomUUID(), "阅读一", "[]")));

        TrainingPlan published = service.publish(planId);

        assertEquals(TrainingPlan.STATUS_PUBLISHED, published.getStatus());
        assertEquals(TrainingPlanVersion.STATUS_PUBLISHED, draft.getStatus());
        assertEquals(1, draft.getTaskCount());
        assertTrue(draft.getPublishedAt() != null);
    }

    @Test
    void retire_draftPlan_rejected() {
        when(planRepository.findByIdAndCompanyId(planId, companyId))
                .thenReturn(Optional.of(plan(TrainingPlan.STATUS_DRAFT, 1)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.retire(planId));

        assertEquals(409, ex.getErrorCode());
    }

    @Test
    void publish_retiredPlan_conflicts() {
        when(planRepository.findByIdAndCompanyId(planId, companyId))
                .thenReturn(Optional.of(plan(TrainingPlan.STATUS_RETIRED, 1)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.publish(planId));

        assertEquals(409, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("已退役"), "退役计划不能被 publish 复活");
    }

    @Test
    void addTask_retiredPlan_conflicts() {
        when(planRepository.findByIdAndCompanyId(planId, companyId))
                .thenReturn(Optional.of(plan(TrainingPlan.STATUS_RETIRED, 1)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.addTask(planId, readingTask()));

        assertEquals(409, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("已退役"));
    }

    // ==================== 新建版本 ====================

    @Test
    void createVersion_remapsPrerequisiteIds() throws Exception {
        TrainingPlan plan = plan(TrainingPlan.STATUS_PUBLISHED, 1);
        when(planRepository.findByIdAndCompanyId(planId, companyId)).thenReturn(Optional.of(plan));
        when(versionRepository.findMaxVersion(planId)).thenReturn(1);
        when(versionRepository.findByPlanIdAndVersion(planId, 1))
                .thenReturn(Optional.of(version(TrainingPlanVersion.STATUS_PUBLISHED)));

        UUID firstId = UUID.randomUUID();
        TrainingTask first = task(firstId, "阅读一", "[]");
        TrainingTask second = task(UUID.randomUUID(), "考试一",
                objectMapper.writeValueAsString(List.of(firstId.toString())));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(first, second));

        TrainingPlanVersion draft = service.createVersion(planId);

        assertEquals(2, draft.getVersion());
        assertEquals(TrainingPlanVersion.STATUS_DRAFT, draft.getStatus());
        assertEquals(2, plan.getCurrentVersion());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TrainingTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(taskRepository).saveAll(captor.capture());
        List<TrainingTask> copies = captor.getValue();
        assertEquals(2, copies.size());
        UUID copiedFirstId = copies.get(0).getId();
        assertTrue(copies.get(1).getPrerequisites().contains(copiedFirstId.toString()),
                "新版本前置必须指向复制后的任务 id，不能残留旧 id");
        assertFalse(copies.get(1).getPrerequisites().contains(firstId.toString()));
        assertEquals(2, copies.get(0).getVersion());
    }

    @Test
    void createVersion_retiredPlan_conflicts() {
        when(planRepository.findByIdAndCompanyId(planId, companyId))
                .thenReturn(Optional.of(plan(TrainingPlan.STATUS_RETIRED, 1)));

        assertEquals(409, assertThrows(BusinessException.class,
                () -> service.createVersion(planId)).getErrorCode());
    }

    @Test
    void createVersion_whenLatestIsDraft_conflicts() {
        when(planRepository.findByIdAndCompanyId(planId, companyId))
                .thenReturn(Optional.of(plan(TrainingPlan.STATUS_PUBLISHED, 1)));
        when(versionRepository.findMaxVersion(planId)).thenReturn(1);
        when(versionRepository.findByPlanIdAndVersion(planId, 1))
                .thenReturn(Optional.of(version(TrainingPlanVersion.STATUS_DRAFT)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createVersion(planId));

        assertEquals(409, ex.getErrorCode());
    }

    // ==================== 辅助 ====================

    @Test
    void updatePhases_reordersAndRenamesOnDraft() {
        TrainingPlan plan = plan(TrainingPlan.STATUS_PUBLISHED, 2);
        plan.setStatus(TrainingPlan.STATUS_DRAFT);
        when(planRepository.findByIdAndCompanyId(planId, companyId)).thenReturn(Optional.of(plan));
        TrainingPlanVersion draftV2 = TrainingPlanVersion.builder()
                .id(UUID.randomUUID()).planId(planId).version(2)
                .status(TrainingPlanVersion.STATUS_DRAFT).build();
        when(versionRepository.findByPlanIdAndVersion(planId, 2))
                .thenReturn(Optional.of(draftV2));
        TrainingTask first = TrainingTask.builder().id(UUID.randomUUID()).planId(planId).version(2)
                .phaseNo(1).phaseName("第一阶段").orderNo(1)
                .type(TrainingTask.TYPE_READING).title("阅读").build();
        TrainingTask second = TrainingTask.builder().id(UUID.randomUUID()).planId(planId).version(2)
                .phaseNo(2).phaseName("第二阶段").orderNo(1)
                .type(TrainingTask.TYPE_READING).title("阅读二").build();
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 2))
                .thenReturn(List.of(first, second));
        when(taskRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service.updatePhases(planId, List.of(
                new com.aiextract.dto.TrainingPhaseRequest.PhaseItem(2, "新第二阶段"),
                new com.aiextract.dto.TrainingPhaseRequest.PhaseItem(1, "新第一阶段")));

        // 原第 2 阶段排到第 1 位 → phaseNo=1；原第 1 阶段 → phaseNo=2
        assertEquals(1, second.getPhaseNo());
        assertEquals("新第二阶段", second.getPhaseName());
        assertEquals(2, first.getPhaseNo());
        assertEquals("新第一阶段", first.getPhaseName());
        verify(taskRepository).saveAll(anyList());
    }

    @Test
    void updatePhases_incompleteSet_conflicts() {
        TrainingPlan plan = plan(TrainingPlan.STATUS_DRAFT, 1);
        when(planRepository.findByIdAndCompanyId(planId, companyId)).thenReturn(Optional.of(plan));
        when(versionRepository.findByPlanIdAndVersion(planId, 1))
                .thenReturn(Optional.of(version(TrainingPlanVersion.STATUS_DRAFT)));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(task(UUID.randomUUID(), "A", "[]")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updatePhases(planId, List.of(
                        new com.aiextract.dto.TrainingPhaseRequest.PhaseItem(9, "不存在的阶段"))));

        assertEquals("PHASE_SET_MISMATCH", ex.getSubCode());
    }

    private void stubDraftVersion() {
        when(planRepository.findByIdAndCompanyId(planId, companyId))
                .thenReturn(Optional.of(plan(TrainingPlan.STATUS_DRAFT, 1)));
        when(versionRepository.findByPlanIdAndVersion(planId, 1))
                .thenReturn(Optional.of(version(TrainingPlanVersion.STATUS_DRAFT)));
        // 只有新增任务的用例会用到顺序号；其余用例共用此桩，故用 lenient 避免误报
        lenient().when(taskRepository.findMaxOrderNo(eq(planId), eq(1), anyInt())).thenReturn(0);
    }

    private TrainingPlan plan(String status, int currentVersion) {
        return TrainingPlan.builder()
                .id(planId).companyId(companyId)
                .name("计划").status(status).currentVersion(currentVersion)
                .build();
    }

    private TrainingPlanVersion version(String status) {
        return TrainingPlanVersion.builder()
                .id(UUID.randomUUID()).planId(planId).version(1).status(status).build();
    }

    private TrainingTask task(UUID id, String title, String prerequisites) {
        return TrainingTask.builder()
                .id(id).planId(planId).version(1).phaseNo(1).orderNo(1)
                .type(TrainingTask.TYPE_READING).title(title)
                .prerequisites(prerequisites)
                .resourceType(TrainingTask.RESOURCE_KNOWLEDGE).resourceId(UUID.randomUUID())
                .build();
    }

    private TrainingTaskRequest readingTask() {
        return TrainingTaskRequest.builder()
                .type(TrainingTask.TYPE_READING)
                .title("阅读一")
                .resourceType(TrainingTask.RESOURCE_KNOWLEDGE)
                .resourceId(UUID.randomUUID())
                .build();
    }

    private TrainingTaskRequest drillTask() {
        return TrainingTaskRequest.builder()
                .type(TrainingTask.TYPE_DRILL)
                .title("价格异议微练")
                .resourceType(TrainingTask.RESOURCE_KNOWLEDGE)
                .resourceId(UUID.randomUUID())
                .sceneTag("价格异议")
                .drillScenario("{\"scenario\":\"客户说太贵了\",\"clientRole\":\"采购负责人\","
                        + "\"questions\":[{\"question\":\"你怎么回应？\","
                        + "\"expectedPoints\":[\"先确认顾虑\",\"再谈价值\"]}]}")
                .build();
    }

    private TrainingTaskRequest examTask() {
        UUID datasetId = UUID.randomUUID();
        // 缺时长的用例会在校验时提前失败，不会读到题集；共用桩故 lenient
        lenient().when(datasetVersionRepository.findByDatasetIdAndVersion(eq(datasetId), eq(1)))
                .thenReturn(Optional.of(EvaluationDatasetVersion.builder()
                        .datasetId(datasetId).version(1)
                        .status(EvaluationDatasetVersion.STATUS_PUBLISHED).build()));
        lenient().when(itemRepository.countByDatasetIdAndVersionAndStatus(eq(datasetId), eq(1), anyString())).thenReturn(3L);
        return TrainingTaskRequest.builder()
                .type(TrainingTask.TYPE_EXAM)
                .title("考试一")
                .examDatasetId(datasetId)
                .examDatasetVersion(1)
                .examDurationMinutes(60)
                .examItemCount(2)
                .build();
    }

    // ==================== 版本对比 ====================

    @Test
    void diffVersions_reportsAddedRemovedAndChanged() {
        TrainingPlan plan = TrainingPlan.builder()
                .id(planId).companyId(companyId).currentVersion(2).build();
        when(planRepository.findByIdAndCompanyId(planId, companyId)).thenReturn(Optional.of(plan));

        UUID oldResource = UUID.randomUUID();
        UUID newResource = UUID.randomUUID();
        TrainingTask v1Reading = TrainingTask.builder()
                .id(UUID.randomUUID()).planId(planId).version(1).phaseNo(1).orderNo(1)
                .title("阅读一").type(TrainingTask.TYPE_READING)
                .resourceType(TrainingTask.RESOURCE_KNOWLEDGE).resourceId(oldResource)
                .isRequired(true).maxAttempts(1).build();
        TrainingTask v1Exam = TrainingTask.builder()
                .id(UUID.randomUUID()).planId(planId).version(1).phaseNo(2).orderNo(2)
                .title("考试二").type(TrainingTask.TYPE_EXAM)
                .isRequired(true).maxAttempts(1).build();
        TrainingTask v2Reading = TrainingTask.builder()
                .id(UUID.randomUUID()).planId(planId).version(2).phaseNo(1).orderNo(1)
                .title("阅读一").type(TrainingTask.TYPE_READING)
                .resourceType(TrainingTask.RESOURCE_KNOWLEDGE).resourceId(newResource)
                .isRequired(true).maxAttempts(3).build();
        TrainingTask v2New = TrainingTask.builder()
                .id(UUID.randomUUID()).planId(planId).version(2).phaseNo(3).orderNo(3)
                .title("新增任务").type(TrainingTask.TYPE_PRACTICE)
                .isRequired(false).maxAttempts(1).build();

        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 1))
                .thenReturn(List.of(v1Reading, v1Exam));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 2))
                .thenReturn(List.of(v2Reading, v2New));

        TrainingPlanService.VersionDiff diff = service.diffVersions(planId, 1, 2);

        assertEquals(1, diff.added().size());
        assertEquals("新增任务", diff.added().get(0).title());
        assertEquals(1, diff.removed().size());
        assertEquals("考试二", diff.removed().get(0).title());
        assertEquals(1, diff.changed().size());
        assertEquals("阅读一", diff.changed().get(0).title());
        assertTrue(diff.changed().get(0).changes().stream()
                .anyMatch(c -> "maxAttempts".equals(c.field())));
        assertTrue(diff.changed().get(0).changes().stream()
                .anyMatch(c -> "resourceId".equals(c.field())));
    }

    @Test
    void diffVersions_sameVersionRejected() {
        TrainingPlan plan = TrainingPlan.builder()
                .id(planId).companyId(companyId).currentVersion(1).build();
        when(planRepository.findByIdAndCompanyId(planId, companyId)).thenReturn(Optional.of(plan));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.diffVersions(planId, 1, 1));
        assertEquals(400, ex.getErrorCode());
    }
}
