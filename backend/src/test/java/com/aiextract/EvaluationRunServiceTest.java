package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.EvaluationRunRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationDataset;
import com.aiextract.model.EvaluationDatasetVersion;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.EvaluationReport;
import com.aiextract.model.EvaluationRun;
import com.aiextract.model.EvaluationRunItem;
import com.aiextract.model.Skill;
import com.aiextract.repository.EvaluationDatasetRepository;
import com.aiextract.repository.EvaluationDatasetVersionRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.EvaluationReportRepository;
import com.aiextract.repository.EvaluationReviewRepository;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.EvaluationRunRepository;
import com.aiextract.repository.SkillProfileRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.service.EvaluationRunAggregator;
import com.aiextract.service.EvaluationRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EvaluationRunService 单元测试 —— 建批次校验、幂等、取消、重试、定稿门槛、对比口径。
 */
@ExtendWith(MockitoExtension.class)
class EvaluationRunServiceTest {

    @Mock
    private EvaluationRunRepository runRepository;
    @Mock
    private EvaluationRunItemRepository runItemRepository;
    @Mock
    private EvaluationItemRepository itemRepository;
    @Mock
    private EvaluationDatasetRepository datasetRepository;
    @Mock
    private EvaluationDatasetVersionRepository versionRepository;
    @Mock
    private EvaluationReportRepository reportRepository;
    @Mock
    private EvaluationReviewRepository reviewRepository;
    @Mock
    private SkillRepository skillRepository;
    @Mock
    private SkillProfileRepository profileRepository;
    @Mock
    private CompanyScopeService companyScopeService;
    @Mock
    private EvaluationRunAggregator aggregator;

    private EvaluationRunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();
    private final UUID skillId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new EvaluationRunService(runRepository, runItemRepository, itemRepository,
                datasetRepository, versionRepository, reportRepository, reviewRepository,
                skillRepository, profileRepository, companyScopeService, aggregator, objectMapper);
        ReflectionTestUtils.setField(service, "defaultModel", "deepseek-chat");
        TokenContext.set(userId, companyId);
        lenient().when(runRepository.findByCompanyIdAndIdempotencyKey(any(), any())).thenReturn(Optional.empty());
        lenient().when(runRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(profileRepository.findBySkillId(any())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void createRun_idempotencyKeyHit_returnsExistingBatch() {
        EvaluationRun existing = EvaluationRun.builder().id(UUID.randomUUID()).build();
        when(runRepository.findByCompanyIdAndIdempotencyKey(companyId, "key-1"))
                .thenReturn(Optional.of(existing));

        EvaluationRun result = service.createRun(request("key-1"));

        assertEquals(existing.getId(), result.getId());
        verify(runRepository, never()).saveAndFlush(any());
        verify(runItemRepository, never()).saveAll(anyList());
    }

    @Test
    void createRun_unpublishedVersion_rejected() {
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset()));
        when(versionRepository.findByDatasetIdAndVersion(datasetId, 1))
                .thenReturn(Optional.of(version(EvaluationDatasetVersion.STATUS_DRAFT)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createRun(request(null)));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("已发布"));
    }

    @Test
    void createRun_purposeMismatch_rejected() {
        EvaluationDataset dataset = dataset();
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset));
        EvaluationRunRequest req = request(null);
        req.setPurpose(EvaluationDataset.PURPOSE_REGRESSION);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createRun(req));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("用途必须与题集用途一致"));
    }

    @Test
    void createRun_organizationSkill_rejected() {
        stubDatasetAndSkill();
        when(versionRepository.findByDatasetIdAndVersion(datasetId, 1))
                .thenReturn(Optional.of(version(EvaluationDatasetVersion.STATUS_PUBLISHED)));
        when(itemRepository.findByDatasetIdAndVersionAndStatus(datasetId, 1, EvaluationItem.STATUS_ACTIVE))
                .thenReturn(List.of(item(), item()));
        when(skillRepository.findById(skillId))
                .thenReturn(Optional.of(Skill.builder().id(skillId).type("organization").build()));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createRun(request(null)));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("组织分身"));
    }

    @Test
    void createRun_createsQueuedBatchWithOneRowPerItem() {
        stubRunnableDataset();

        EvaluationRun run = service.createRun(request(null));

        assertEquals(EvaluationRun.STATUS_QUEUED, run.getStatus());
        assertEquals(EvaluationRun.REVIEW_NOT_READY, run.getReviewStatus());
        assertEquals(2, run.getTotalItems());
        ArgumentCaptor<List<EvaluationRunItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(runItemRepository).saveAll(captor.capture());
        List<EvaluationRunItem> rows = captor.getValue();
        assertEquals(2, rows.size());
        assertEquals(EvaluationRunItem.STATUS_PENDING, rows.get(0).getStatus());
        assertEquals(1, rows.get(0).getAttemptNo());
        assertEquals(run.getId(), rows.get(0).getRunId());
    }

    @Test
    void createRun_emptyItems_rejected() {
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset()));
        when(versionRepository.findByDatasetIdAndVersion(datasetId, 1))
                .thenReturn(Optional.of(version(EvaluationDatasetVersion.STATUS_PUBLISHED)));
        when(itemRepository.findByDatasetIdAndVersionAndStatus(datasetId, 1, EvaluationItem.STATUS_ACTIVE))
                .thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createRun(request(null)));

        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("没有可运行题目"));
    }

    @Test
    void cancelRun_terminalBatch_rejected() {
        EvaluationRun run = EvaluationRun.builder().id(UUID.randomUUID())
                .status(EvaluationRun.STATUS_COMPLETED).build();
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(run));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.cancelRun(run.getId()));

        assertEquals(409, ex.getErrorCode());
    }

    @Test
    void retryFailed_createsNextAttemptAndRequeues() {
        UUID runId = UUID.randomUUID();
        EvaluationRun run = EvaluationRun.builder().id(runId).status(EvaluationRun.STATUS_PARTIAL_FAILED).build();
        EvaluationRunItem failed = EvaluationRunItem.builder()
                .id(UUID.randomUUID()).runId(runId).itemId(UUID.randomUUID())
                .itemVersion(1).status(EvaluationRunItem.STATUS_ERROR).attemptNo(1).build();
        EvaluationRunItem ok = EvaluationRunItem.builder()
                .id(UUID.randomUUID()).runId(runId).itemId(UUID.randomUUID())
                .itemVersion(1).status(EvaluationRunItem.STATUS_COMPLETED).attemptNo(1).build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runItemRepository.findLatestAttempts(runId)).thenReturn(List.of(failed, ok));

        int retried = service.retryFailed(runId);

        assertEquals(1, retried);
        ArgumentCaptor<List<EvaluationRunItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(runItemRepository).saveAll(captor.capture());
        assertEquals(2, captor.getValue().get(0).getAttemptNo());
        verify(runRepository).requeue(eq(runId), any());
    }

    @Test
    void retryFailed_rejectsCancelledRun() {
        UUID runId = UUID.randomUUID();
        EvaluationRun run = EvaluationRun.builder().id(runId).status(EvaluationRun.STATUS_CANCELLED).build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.retryFailed(runId));

        assertEquals(409, ex.getErrorCode());
        verify(runItemRepository, never()).saveAll(any());
        verify(runRepository, never()).requeue(any(), any());
    }

    @Test
    void finalizeRun_blocksWhenNeedReviewItemsUnreviewed() {
        UUID runId = UUID.randomUUID();
        EvaluationRun run = EvaluationRun.builder().id(runId)
                .status(EvaluationRun.STATUS_COMPLETED).build();
        EvaluationRunItem needReview = EvaluationRunItem.builder()
                .id(UUID.randomUUID()).runId(runId).status(EvaluationRunItem.STATUS_COMPLETED)
                .verdict(EvaluationRunItem.VERDICT_PASS)
                .aiScore("{\"total_score\":62,\"need_review\":true}")
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runItemRepository.findLatestAttempts(runId)).thenReturn(List.of(needReview));
        when(reviewRepository.findByRunItemIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.finalizeRun(runId));

        assertEquals(409, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("需人工复核"));
        verify(reportRepository, never()).save(any());
    }

    @Test
    void finalizeRun_freezesVersionedReport() {
        UUID runId = UUID.randomUUID();
        EvaluationRun run = EvaluationRun.builder().id(runId)
                .datasetId(datasetId).datasetVersion(1).skillId(skillId)
                .purpose("acceptance").status(EvaluationRun.STATUS_COMPLETED)
                .startedAt(LocalDateTime.now().minusMinutes(2)).finishedAt(LocalDateTime.now())
                .build();
        EvaluationRunItem done = EvaluationRunItem.builder()
                .id(UUID.randomUUID()).runId(runId).status(EvaluationRunItem.STATUS_COMPLETED)
                .verdict(EvaluationRunItem.VERDICT_PASS)
                .aiScore("{\"total_score\":88,\"need_review\":false}")
                // Worker 执行时写入的检索指标：金标 2 条命中 1 条，MRR 0.5
                .ruleCheckResult("{\"retrieval_metrics\":{\"expectedCount\":2,\"hitExpected\":1,"
                        + "\"recall\":0.5,\"precisionAtK\":0.5,\"mrr\":0.5,\"missedExpected\":[\"报价策略\"]}}")
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(runItemRepository.findLatestAttempts(runId)).thenReturn(List.of(done));
        when(reportRepository.findFirstByRunIdOrderByVersionDesc(runId)).thenReturn(Optional.empty());

        EvaluationReport report = service.finalizeRun(runId);

        assertEquals(1, report.getVersion());
        assertEquals(EvaluationReport.STATUS_FINALIZED, report.getStatus());
        assertTrue(report.getContent().contains("评测验收报告"));
        // 检索指标进入报告与批次明细
        assertTrue(report.getContent().contains("Recall@K"), "报告应包含 Recall@K 段");
        assertTrue(report.getSummary().contains("\"recallAtK\":0.5"));
        verify(runRepository).updateScoreDetail(eq(runId), any(), any());
        verify(runRepository).updateReviewStatus(eq(runId), eq(EvaluationRun.REVIEW_FINALIZED), any());
    }

    @Test
    void finalizeRun_alreadyFinalized_returnsExistingReportWithoutNewVersion() {
        UUID runId = UUID.randomUUID();
        EvaluationRun run = EvaluationRun.builder().id(runId)
                .status(EvaluationRun.STATUS_COMPLETED)
                .reviewStatus(EvaluationRun.REVIEW_FINALIZED)
                .build();
        EvaluationReport existing = EvaluationReport.builder()
                .id(UUID.randomUUID()).runId(runId).version(2)
                .status(EvaluationReport.STATUS_FINALIZED).build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(reportRepository.findFirstByRunIdOrderByVersionDesc(runId)).thenReturn(Optional.of(existing));

        EvaluationReport report = service.finalizeRun(runId);

        assertEquals(2, report.getVersion());
        verify(reportRepository, never()).save(any());
    }

    @Test
    void compare_differentVersions_marksCaliberDifference() {
        UUID leftId = UUID.randomUUID();
        UUID rightId = UUID.randomUUID();
        EvaluationRun left = EvaluationRun.builder().id(leftId).datasetId(datasetId).datasetVersion(1).build();
        EvaluationRun right = EvaluationRun.builder().id(rightId).datasetId(datasetId).datasetVersion(2).build();
        UUID commonItem = UUID.randomUUID();
        when(runRepository.findById(leftId)).thenReturn(Optional.of(left));
        when(runRepository.findById(rightId)).thenReturn(Optional.of(right));
        when(runItemRepository.findLatestAttempts(leftId)).thenReturn(List.of(
                EvaluationRunItem.builder().id(UUID.randomUUID()).itemId(commonItem)
                        .verdict(EvaluationRunItem.VERDICT_PASS).aiScore("{\"total_score\":80}").build()));
        when(runItemRepository.findLatestAttempts(rightId)).thenReturn(List.of(
                EvaluationRunItem.builder().id(UUID.randomUUID()).itemId(commonItem)
                        .verdict(EvaluationRunItem.VERDICT_FAIL).aiScore("{\"total_score\":40}").build()));

        EvaluationRunService.CompareResult result = service.compare(leftId, rightId);

        assertFalse(result.sameCaliber());
        assertTrue(result.caliberNote().contains("不可直接相减总分"));
        assertEquals(1, result.diffs().size());
        assertEquals(Boolean.TRUE, result.diffs().get(0).get("verdictChanged"));
    }

    // ==================== 辅助 ====================

    private EvaluationRunRequest request(String idempotencyKey) {
        return EvaluationRunRequest.builder()
                .datasetId(datasetId)
                .skillId(skillId)
                .purpose(EvaluationDataset.PURPOSE_ACCEPTANCE)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    private EvaluationDataset dataset() {
        return EvaluationDataset.builder()
                .id(datasetId)
                .purpose(EvaluationDataset.PURPOSE_ACCEPTANCE)
                .currentVersion(1)
                .build();
    }

    private EvaluationDatasetVersion version(String status) {
        return EvaluationDatasetVersion.builder()
                .id(UUID.randomUUID()).datasetId(datasetId).version(1).status(status).build();
    }

    private EvaluationItem item() {
        return EvaluationItem.builder().id(UUID.randomUUID()).question("q").build();
    }

    private void stubDatasetAndSkill() {
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset()));
        when(skillRepository.findById(skillId))
                .thenReturn(Optional.of(Skill.builder().id(skillId).type("individual").build()));
    }

    private void stubRunnableDataset() {
        stubDatasetAndSkill();
        when(versionRepository.findByDatasetIdAndVersion(datasetId, 1))
                .thenReturn(Optional.of(version(EvaluationDatasetVersion.STATUS_PUBLISHED)));
        when(itemRepository.findByDatasetIdAndVersionAndStatus(datasetId, 1, EvaluationItem.STATUS_ACTIVE))
                .thenReturn(List.of(item(), item()));
    }
}
