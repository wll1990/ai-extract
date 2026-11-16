package com.aiextract;

import com.aiextract.model.EvaluationItem;
import com.aiextract.model.EvaluationRun;
import com.aiextract.model.EvaluationRunItem;
import com.aiextract.model.Skill;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.EvaluationRunRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.service.EvaluationAnswerService;
import com.aiextract.service.EvaluationExecutionStateService;
import com.aiextract.service.EvaluationJudgeService;
import com.aiextract.service.EvaluationLeaseService;
import com.aiextract.service.EvaluationRunAggregator;
import com.aiextract.service.RetrievalMetricsService;
import com.aiextract.service.EvaluationWorker;
import com.aiextract.service.KnowledgeCaseRetriever;
import com.aiextract.service.KnowledgeUsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * EvaluationWorker 单元测试 —— 验证领取→执行→写回→触发聚合，以及失败与取消分支。
 *
 * <p>计数与收尾口径由 {@link EvaluationRunAggregatorTest} 覆盖，这里只验证 Worker 调用了聚合器。</p>
 */
@ExtendWith(MockitoExtension.class)
class EvaluationWorkerTest {

    @Mock
    private EvaluationRunRepository runRepository;
    @Mock
    private EvaluationRunItemRepository runItemRepository;
    @Mock
    private EvaluationItemRepository itemRepository;
    @Mock
    private SkillRepository skillRepository;
    @Mock
    private EvaluationLeaseService leaseService;
    @Mock
    private EvaluationExecutionStateService stateService;
    @Mock
    private EvaluationAnswerService answerService;
    @Mock
    private EvaluationJudgeService judgeService;
    @Mock
    private EvaluationRunAggregator aggregator;
    @Mock
    private RetrievalMetricsService metricsService;
    @Mock
    private KnowledgeUsageService knowledgeUsageService;

    private EvaluationWorker worker;

    private final UUID runId = UUID.randomUUID();
    private final UUID runItemId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID skillId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        worker = new EvaluationWorker(runRepository, runItemRepository, itemRepository,
                skillRepository, leaseService, stateService, answerService, judgeService,
                metricsService, knowledgeUsageService, aggregator);
        ReflectionTestUtils.setField(worker, "workerEnabled", true);
        ReflectionTestUtils.setField(worker, "runsPerTick", 5);
        ReflectionTestUtils.setField(worker, "itemsPerTick", 2);
    }

    @Test
    void tick_completesItemAndTriggersAggregation() {
        stubRun(EvaluationRun.STATUS_RUNNING);
        when(leaseService.claim(eq(runId), anyString(), eq(2))).thenReturn(List.of(runItemId));
        stubItemGraph();
        when(answerService.answer(any(), any(), eq(companyId)))
                .thenReturn(new EvaluationAnswerService.AnswerResult("答案正文", "{\"grains\":[]}",
                        new KnowledgeCaseRetriever.KnowledgeCaseHits(java.util.List.of(), java.util.List.of())));
        when(judgeService.judge(any(), any(), eq("答案正文"), eq("acceptance")))
                .thenReturn(new EvaluationJudgeService.JudgeOutcome(
                        "{\"total_score\":85}", "{\"forbidden_hit\":false}", EvaluationRunItem.VERDICT_PASS,
                        false, "覆盖要点"));
        // 检索指标挂在规则检查 JSON 上，随结果一起落库
        when(metricsService.compute(any(), any())).thenReturn(
                new RetrievalMetricsService.Metrics(1, 2, 1, 1, 1.0, 0.5, 0.5, java.util.List.of()));
        when(metricsService.attachTo(any(), any()))
                .thenReturn("{\"forbidden_hit\":false,\"retrieval_metrics\":{}}");

        worker.tick();

        verify(stateService).markRunning(runId);
        // 答题后先落证据再评分，评分失败也不丢回答
        verify(stateService).markScoring(eq(runItemId), anyString(), eq("答案正文"), eq("{\"grains\":[]}"));
        verify(stateService).completeItem(eq(runItemId), anyString(), eq(EvaluationRunItem.VERDICT_PASS),
                eq("答案正文"), eq("{\"grains\":[]}"),
                eq("{\"forbidden_hit\":false,\"retrieval_metrics\":{}}"), eq("{\"total_score\":85}"));
        verify(aggregator).recomputeAndMaybeFinish(runId);
    }

    @Test
    void tick_answerFailure_marksErrorInsteadOfFakeScore() {
        stubRun(EvaluationRun.STATUS_RUNNING);
        when(leaseService.claim(eq(runId), anyString(), eq(2))).thenReturn(List.of(runItemId));
        when(runItemRepository.findById(runItemId)).thenReturn(Optional.of(runItem()));
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item()));
        when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill()));
        when(answerService.answer(any(), any(), any())).thenThrow(new IllegalStateException("模型超时"));

        worker.tick();

        verify(stateService).markError(eq(runItemId), anyString(),
                eq("模型响应超时，请稍后重试（AI_TIMEOUT）"));
        verify(stateService, never()).completeItem(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(aggregator).recomputeAndMaybeFinish(runId);
    }

    @Test
    void tick_missingItem_marksErrorWithoutCallingModel() {
        stubRun(EvaluationRun.STATUS_RUNNING);
        when(leaseService.claim(eq(runId), anyString(), eq(2))).thenReturn(List.of(runItemId));
        when(runItemRepository.findById(runItemId)).thenReturn(Optional.of(runItem()));
        when(itemRepository.findById(itemId)).thenReturn(Optional.empty());

        worker.tick();

        verify(stateService).markError(eq(runItemId), anyString(), anyString());
        verifyNoInteractions(answerService, judgeService);
    }

    @Test
    void tick_noClaimableItems_stillRecomputesCounters() {
        stubRun(EvaluationRun.STATUS_RUNNING);
        when(leaseService.claim(eq(runId), anyString(), eq(2))).thenReturn(List.of());

        worker.tick();

        verify(aggregator).recomputeAndMaybeFinish(runId);
        verifyNoInteractions(answerService, judgeService);
    }

    @Test
    void tick_cancelRequested_releasesLeaseAndCancels() {
        stubRun(EvaluationRun.STATUS_CANCEL_REQUESTED);

        worker.tick();

        verify(stateService).cancel(eq(runId), anyString());
        verify(leaseService, never()).claim(any(), anyString(), anyInt());
        verifyNoInteractions(aggregator);
    }

    @Test
    void tick_disabled_doesNothing() {
        ReflectionTestUtils.setField(worker, "workerEnabled", false);

        worker.tick();

        verifyNoInteractions(runRepository, leaseService, runItemRepository, aggregator, stateService);
    }

    @Test
    void heartbeat_renewsLeasesForThisWorker() {
        worker.heartbeat();

        verify(leaseService).renewAll(anyString());
    }

    private void stubRun(String status) {
        EvaluationRun run = EvaluationRun.builder()
                .id(runId)
                .status(status)
                .companyId(companyId)
                .skillId(skillId)
                .purpose("acceptance")
                .build();
        when(runRepository.findActiveRunIds(5)).thenReturn(List.of(runId));
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
    }

    private void stubItemGraph() {
        when(runItemRepository.findById(runItemId)).thenReturn(Optional.of(runItem()));
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item()));
        when(skillRepository.findById(skillId)).thenReturn(Optional.of(skill()));
    }

    private EvaluationRunItem runItem() {
        return EvaluationRunItem.builder()
                .id(runItemId)
                .runId(runId)
                .itemId(itemId)
                .status(EvaluationRunItem.STATUS_GENERATING)
                .build();
    }

    private EvaluationItem item() {
        return EvaluationItem.builder()
                .id(itemId)
                .question("复购率怎么算？")
                .expectedPoints("[\"要点\"]")
                .build();
    }

    private Skill skill() {
        return Skill.builder().id(skillId).type("individual").build();
    }
}
