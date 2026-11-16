package com.aiextract;

import com.aiextract.config.PromptLoader;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationDataset;
import com.aiextract.model.EvaluationGenerationJob;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.EvaluationDatasetRepository;
import com.aiextract.repository.EvaluationGenerationJobRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.service.EvaluationGenerationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EvaluationGenerationService 单元测试 —— 状态流转、字段映射与事实级去重（docs/40）。
 *
 * <p>素材固定为三句话，候选通过 {@code factQuote} 指定所检验的事实：
 * S1「复购率=重复购买客户数/总客户数」、S2「客户满意度按月调研统计」、S3「客单价按订单金额均值计算」。</p>
 */
@ExtendWith(MockitoExtension.class)
class EvaluationGenerationServiceTest {

    @Mock
    private EvaluationDatasetRepository datasetRepository;
    @Mock
    private EvaluationGenerationJobRepository jobRepository;
    @Mock
    private EvaluationItemRepository itemRepository;
    @Mock
    private KnowledgeEntryRepository knowledgeRepository;
    @Mock
    private IndustryCaseRepository caseRepository;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callResponseSpec;
    @Mock
    private PromptLoader promptLoader;

    private EvaluationGenerationService service;

    private final UUID datasetId = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private static final String S1 = "复购率=重复购买客户数/总客户数";
    private static final String S2 = "客户满意度按月调研统计";
    private static final String S3 = "客单价按订单金额均值计算";

    @BeforeEach
    void setUp() {
        service = new EvaluationGenerationService(
                datasetRepository, jobRepository, itemRepository,
                knowledgeRepository, caseRepository, chatClient, promptLoader,
                new ObjectMapper());
        ReflectionTestUtils.setField(service, "self", service);
        lenient().when(promptLoader.format(anyString(), anyMap())).thenReturn("prompt");
        lenient().when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== createJob ====================

    @Test
    void createJob_rejectsNonDraft() {
        when(datasetRepository.findById(datasetId))
                .thenReturn(Optional.of(dataset(EvaluationDataset.STATUS_PUBLISHED)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createJob(datasetId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId)));

        assertEquals(400, ex.getErrorCode());
    }

    @Test
    void createJob_rejectsIllegalSourceType() {
        when(datasetRepository.findById(datasetId))
                .thenReturn(Optional.of(dataset(EvaluationDataset.STATUS_DRAFT)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createJob(datasetId, "pdf", List.of(sourceId)));

        assertEquals(400, ex.getErrorCode());
    }

    @Test
    void createJob_createsRunningJob() {
        when(datasetRepository.findById(datasetId))
                .thenReturn(Optional.of(dataset(EvaluationDataset.STATUS_DRAFT)));

        UUID id = service.createJob(datasetId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        assertNotNull(id);
        ArgumentCaptor<EvaluationGenerationJob> captor = ArgumentCaptor.forClass(EvaluationGenerationJob.class);
        verify(jobRepository).saveAndFlush(captor.capture());
        assertEquals(EvaluationGenerationJob.STATUS_RUNNING, captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getProducedCount());
    }

    @Test
    void createJob_rejectsConcurrentDuplicateViaUniqueIndex() {
        when(datasetRepository.findById(datasetId))
                .thenReturn(Optional.of(dataset(EvaluationDataset.STATUS_DRAFT)));
        when(jobRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_gen_job_running"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createJob(datasetId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId)));

        assertEquals(409, ex.getErrorCode());
    }

    @Test
    void createJob_marksStaleRunningJobFailedAndProceeds() {
        when(datasetRepository.findById(datasetId))
                .thenReturn(Optional.of(dataset(EvaluationDataset.STATUS_DRAFT)));
        EvaluationGenerationJob stale = EvaluationGenerationJob.builder()
                .id(UUID.randomUUID()).datasetId(datasetId)
                .sourceType(EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE)
                .status(EvaluationGenerationJob.STATUS_RUNNING).producedCount(0)
                .createdAt(LocalDateTime.now().minusHours(2)).updatedAt(LocalDateTime.now().minusHours(2))
                .build();
        when(jobRepository.findFirstByDatasetIdAndStatusOrderByCreatedAtDesc(datasetId, "running"))
                .thenReturn(Optional.of(stale));

        service.createJob(datasetId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        assertEquals(EvaluationGenerationJob.STATUS_FAILED, stale.getStatus());
        verify(jobRepository).saveAndFlush(stale);
    }

    // ==================== 基本流转 ====================

    @Test
    void runGeneration_marksFailed_whenDatasetMissing() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job()));
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.empty());

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        ArgumentCaptor<EvaluationGenerationJob> captor = ArgumentCaptor.forClass(EvaluationGenerationJob.class);
        verify(jobRepository).save(captor.capture());
        assertEquals(EvaluationGenerationJob.STATUS_FAILED, captor.getValue().getStatus());
    }

    @Test
    void runGeneration_skipsDeletedSource_marksDoneZero() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job()));
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset(EvaluationDataset.STATUS_DRAFT)));
        when(knowledgeRepository.findById(sourceId)).thenReturn(Optional.empty());

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        ArgumentCaptor<EvaluationGenerationJob> captor = ArgumentCaptor.forClass(EvaluationGenerationJob.class);
        verify(jobRepository).save(captor.capture());
        assertEquals(EvaluationGenerationJob.STATUS_DONE, captor.getValue().getStatus());
        assertEquals(0, captor.getValue().getProducedCount());
    }

    @Test
    void runGeneration_generatesItemWithFactKeyAndSnapshot() {
        stubGenerationCall();
        when(callResponseSpec.content()).thenReturn(json(candidate("复购率怎么算？", S1)));

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        ArgumentCaptor<List<EvaluationItem>> itemsCaptor = itemsCaptor();
        verify(itemRepository).saveAll(itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size());
        EvaluationItem item = itemsCaptor.getValue().get(0);
        assertEquals("复购率怎么算？", item.getQuestion());
        assertEquals(datasetId, item.getDatasetId());
        assertEquals(1, item.getVersion());
        // AI 只出草稿：必须人工审核后才 active
        assertEquals(EvaluationItem.STATUS_DRAFT, item.getStatus());
        assertEquals(Boolean.TRUE, item.getGenerated());
        assertEquals("[\"复购率=重复购买客户数/总客户数\"]", item.getExpectedPoints());
        assertEquals("复购率定义", item.getGoldBasis());
        // 标识落在素材句子上，原文一并冻结便于人工核对
        assertEquals(S1, item.getFactQuote());
        assertNotNull(item.getFactKey());
        assertEquals(64, item.getFactKey().length());
    }

    @Test
    void runGeneration_assignsSequentialOrderNo() {
        stubGenerationCall();
        when(itemRepository.findMaxOrderNo(datasetId, 1)).thenReturn(2);
        when(callResponseSpec.content()).thenReturn(json(
                candidate("q1", S1), candidate("q2", S2), candidate("q3", S3)));

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        ArgumentCaptor<List<EvaluationItem>> itemsCaptor = itemsCaptor();
        verify(itemRepository).saveAll(itemsCaptor.capture());
        List<EvaluationItem> items = itemsCaptor.getValue();
        assertEquals(3, items.size());
        assertEquals(3, items.get(0).getOrderNo());
        assertEquals(4, items.get(1).getOrderNo());
        assertEquals(5, items.get(2).getOrderNo());
    }

    @Test
    void runGeneration_fallsBackGoldBasisToSourceTitle() {
        stubGenerationCall();
        when(callResponseSpec.content()).thenReturn(
                "[{\"question\":\"复购率怎么算？\",\"factQuote\":\"" + S1 + "\",\"expectedPoints\":[\"p\"],\"goldBasis\":null}]");

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        ArgumentCaptor<List<EvaluationItem>> itemsCaptor = itemsCaptor();
        verify(itemRepository).saveAll(itemsCaptor.capture());
        assertEquals("复购率定义", itemsCaptor.getValue().get(0).getGoldBasis());
    }

    @Test
    void runGeneration_marksFailed_onAiException() {
        stubGenerationCall();
        when(requestSpec.call()).thenThrow(new RuntimeException("AI 超时"));

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        ArgumentCaptor<EvaluationGenerationJob> captor = ArgumentCaptor.forClass(EvaluationGenerationJob.class);
        verify(jobRepository).save(captor.capture());
        assertEquals(EvaluationGenerationJob.STATUS_FAILED, captor.getValue().getStatus());
        assertEquals("模型响应超时，请稍后重试（AI_TIMEOUT）", captor.getValue().getErrorMessage());
    }

    // ==================== 精确去重 ====================

    @Test
    void runGeneration_skipsCandidateDuplicatingExistingQuestion() {
        stubGenerationCall();
        when(itemRepository.findQuestionsByDatasetIdAndVersionAndStatus(
                datasetId, 1, EvaluationItem.STATUS_ACTIVE)).thenReturn(List.of("复购率 怎么算？"));
        when(callResponseSpec.content()).thenReturn(json(
                candidate("复购率怎么算？", S1), candidate("首访怎么开场？", S2)));

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        ArgumentCaptor<List<EvaluationItem>> itemsCaptor = itemsCaptor();
        verify(itemRepository).saveAll(itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size());
        assertEquals("首访怎么开场？", itemsCaptor.getValue().get(0).getQuestion());
        assertEquals(1, itemsCaptor.getValue().get(0).getOrderNo());
    }

    @Test
    void runGeneration_skipsDuplicateWithinSameBatch() {
        stubGenerationCall();
        when(itemRepository.findMaxOrderNo(datasetId, 1)).thenReturn(4);
        when(callResponseSpec.content()).thenReturn(json(
                candidate("客户说贵怎么回应？", S1),
                candidate("客户说贵怎么回应？", S1),
                candidate("如何报价？", S2)));

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        ArgumentCaptor<List<EvaluationItem>> itemsCaptor = itemsCaptor();
        verify(itemRepository).saveAll(itemsCaptor.capture());
        assertEquals(2, itemsCaptor.getValue().size());
        assertEquals(5, itemsCaptor.getValue().get(0).getOrderNo());
        assertEquals(6, itemsCaptor.getValue().get(1).getOrderNo());
    }

    // ==================== 事实级去重 ====================

    /** 已有题目已覆盖 S1（fact_key 相同）→ 引用 S1 的候选必须丢弃。 */
    @Test
    void runGeneration_skipsCandidateWhoseFactAlreadyCovered() {
        stubGenerationCall();
        when(itemRepository.findFactKeysByDatasetIdAndVersionAndStatus(datasetId, 1, EvaluationItem.STATUS_ACTIVE))
                .thenReturn(List.of(factKeyOf(S1)));
        when(callResponseSpec.content()).thenReturn(json(
                candidate("换个问法再问复购率", S1), candidate("满意度怎么统计？", S2)));

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        ArgumentCaptor<List<EvaluationItem>> itemsCaptor = itemsCaptor();
        verify(itemRepository).saveAll(itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size());
        assertEquals("满意度怎么统计？", itemsCaptor.getValue().get(0).getQuestion());
    }

    /** 同一素材里未覆盖的事实（S2）必须照常出题。 */
    @Test
    void runGeneration_acceptsCandidateForDifferentFact() {
        stubGenerationCall();
        when(itemRepository.findFactKeysByDatasetIdAndVersionAndStatus(datasetId, 1, EvaluationItem.STATUS_ACTIVE))
                .thenReturn(List.of(factKeyOf(S1)));
        when(callResponseSpec.content()).thenReturn(json(candidate("满意度怎么统计？", S2)));

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        ArgumentCaptor<List<EvaluationItem>> itemsCaptor = itemsCaptor();
        verify(itemRepository).saveAll(itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size());
        assertEquals(S2, itemsCaptor.getValue().get(0).getFactQuote());
    }

    /** 同批次两条候选引用同一句 → 只保留第一条。 */
    @Test
    void runGeneration_dedupsIntraBatchByFact() {
        stubGenerationCall();
        when(callResponseSpec.content()).thenReturn(json(
                candidate("复购率怎么算？", S1), candidate("复购率多少算健康？", S1)));

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        ArgumentCaptor<List<EvaluationItem>> itemsCaptor = itemsCaptor();
        verify(itemRepository).saveAll(itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size());
        assertEquals("复购率怎么算？", itemsCaptor.getValue().get(0).getQuestion());
    }

    /** 引用同一句的不同片段（不同措辞/不同截取）必须落到同一个事实标识上。 */
    @Test
    void runGeneration_snapsDifferentSpansOfSameSentenceToSameFact() {
        stubGenerationCall();
        when(callResponseSpec.content()).thenReturn(json(
                candidate("复购率怎么算？", "复购率=重复购买客户数"),
                candidate("复购率的分子分母是什么？", "重复购买客户数/总客户数")));

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        ArgumentCaptor<List<EvaluationItem>> itemsCaptor = itemsCaptor();
        verify(itemRepository).saveAll(itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size(), "同一句的两个片段应判为同一事实");
    }

    /** 引用素材里不存在的片段 → 该题丢弃，同批合法题照常入库。 */
    @Test
    void runGeneration_rejectsCandidateWithoutLocatableQuote() {
        stubGenerationCall();
        when(callResponseSpec.content()).thenReturn(json(
                candidate("编造的题", "这段话素材里根本没有"),
                candidate("满意度怎么统计？", S2)));

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        ArgumentCaptor<List<EvaluationItem>> itemsCaptor = itemsCaptor();
        verify(itemRepository).saveAll(itemsCaptor.capture());
        assertEquals(1, itemsCaptor.getValue().size());
        assertEquals("满意度怎么统计？", itemsCaptor.getValue().get(0).getQuestion());
    }

    /** 缺 factQuote 的候选一律作废（没有去重依据的题不允许进题集）。 */
    @Test
    void runGeneration_rejectsCandidateWithoutFactQuote() {
        stubGenerationCall();
        when(callResponseSpec.content()).thenReturn(
                "[{\"question\":\"没有事实引用的题\",\"expectedPoints\":[\"p\"]}]");

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        // 空结果不写库（persistItems 内部跳过 saveAll），但任务仍要正常收尾
        ArgumentCaptor<EvaluationGenerationJob> jobCaptor = ArgumentCaptor.forClass(EvaluationGenerationJob.class);
        verify(jobRepository).save(jobCaptor.capture());
        assertEquals(EvaluationGenerationJob.STATUS_DONE, jobCaptor.getValue().getStatus());
        assertEquals(0, jobCaptor.getValue().getProducedCount());
    }

    // ==================== Prompt 变量 ====================

    @Test
    void runGeneration_passesExistingQuestionsToPrompt() {
        stubGenerationCall();
        when(itemRepository.findQuestionsByDatasetIdAndVersionAndStatus(
                datasetId, 1, EvaluationItem.STATUS_ACTIVE)).thenReturn(List.of("复购率怎么算？"));
        when(callResponseSpec.content()).thenReturn("[]");

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        assertTrue(promptVars().get("exclude_questions").contains("复购率怎么算？"));
    }

    @Test
    void runGeneration_passesCoveredFactsToPrompt() {
        stubGenerationCall();
        when(itemRepository.findFactQuotesByDatasetIdAndVersionAndStatus(
                datasetId, 1, EvaluationItem.STATUS_ACTIVE)).thenReturn(List.of(S1));
        when(callResponseSpec.content()).thenReturn("[]");

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        assertTrue(promptVars().get("exclude_facts").contains(S1), "已覆盖事实必须下发避让");
    }

    @Test
    void runGeneration_usesPlaceholdersWhenNothingCovered() {
        stubGenerationCall();
        when(callResponseSpec.content()).thenReturn("[]");

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        Map<String, String> vars = promptVars();
        assertEquals("（本题集暂无已有题目）", vars.get("exclude_questions"));
        assertEquals("（本题集暂无已覆盖事实）", vars.get("exclude_facts"));
    }

    @Test
    void runGeneration_promptIsFormattedOncePerSource() {
        stubGenerationCall();
        when(callResponseSpec.content()).thenReturn("[]");

        service.runGeneration(jobId, EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE, List.of(sourceId));

        verify(promptLoader, times(1)).format(eq("eval_generate_item.md"), anyMap());
    }

    // ==================== 工具 ====================

    /** runGeneration 走到「调 AI」之前的所有依赖桩。 */
    private void stubGenerationCall() {
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job()));
        when(datasetRepository.findById(datasetId)).thenReturn(Optional.of(dataset(EvaluationDataset.STATUS_DRAFT)));
        when(knowledgeRepository.findById(sourceId)).thenReturn(Optional.of(knowledge()));
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    /** 取一次 prompt 变量快照（只调用过一次 format 的用例适用）。 */
    @SuppressWarnings("unchecked")
    private Map<String, String> promptVars() {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptLoader).format(eq("eval_generate_item.md"), captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<EvaluationItem>> itemsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    /** 单条候选 JSON：题干 + 事实引用。 */
    private String candidate(String question, String factQuote) {
        return "{\"question\":\"" + question + "\",\"factQuote\":\"" + factQuote + "\","
                + "\"context\":\"\",\"expectedPoints\":[\"复购率=重复购买客户数/总客户数\"],"
                + "\"forbiddenPoints\":[],\"citationRequirement\":null,\"sceneTag\":\"术语\","
                + "\"goldBasis\":\"复购率定义\",\"retrievalExpected\":[\"复购率定义\"]}";
    }

    private String json(String... candidates) {
        return "[" + String.join(",", candidates) + "]";
    }

    /** 与生产实现同算法的参照哈希：归一化后 SHA-256。 */
    private String factKeyOf(String sentence) {
        try {
            StringBuilder normalized = new StringBuilder();
            for (char c : sentence.toCharArray()) {
                if (Character.isLetterOrDigit(c)) {
                    normalized.append(Character.toLowerCase(c));
                }
            }
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(normalized.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private EvaluationDataset dataset(String status) {
        return EvaluationDataset.builder()
                .id(datasetId).companyId(null).name("题集")
                .purpose(EvaluationDataset.PURPOSE_REGRESSION)
                .status(status).currentVersion(1).itemCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    private EvaluationGenerationJob job() {
        return EvaluationGenerationJob.builder()
                .id(jobId).datasetId(datasetId)
                .sourceType(EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE)
                .status(EvaluationGenerationJob.STATUS_RUNNING).producedCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    /** 三句话的素材：S1/S2/S3 分别可被独立检验。 */
    private KnowledgeEntry knowledge() {
        return KnowledgeEntry.builder()
                .id(sourceId).title("复购率定义").category("terminology")
                .content(S1 + "。" + S2 + "。" + S3 + "。")
                .version(1).status(KnowledgeEntry.STATUS_ACTIVE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }
}
