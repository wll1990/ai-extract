package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.PromptLoader;
import com.aiextract.model.IndustryCase;
import com.aiextract.repository.ContentGenerationJobRepository;
import com.aiextract.repository.EvaluationDatasetRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.EvaluationRunRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.service.ChatStreamAdapter;
import com.aiextract.service.ContentGenerationService;
import com.aiextract.service.ContentSignalService;
import com.aiextract.service.TrainingAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 案例草稿落库：JSONB 字段必须写成合法 JSON。
 *
 * <p>生产事故：模型按文本返回 customerProfile，直接写 JSONB 列导致
 * {@code invalid input syntax for type json}，整条草稿插入失败。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContentGenerationServiceTest {

    @Mock private ChatStreamAdapter chatStreamAdapter;
    @Mock private PromptLoader promptLoader;
    @Mock private KnowledgeEntryRepository knowledgeRepository;
    @Mock private IndustryCaseRepository caseRepository;
    @Mock private EvaluationDatasetRepository datasetRepository;
    @Mock private EvaluationItemRepository itemRepository;
    @Mock private EvaluationRunRepository runRepository;
    @Mock private EvaluationRunItemRepository runItemRepository;
    @Mock private ContentGenerationJobRepository jobRepository;
    @Mock private ContentSignalService signalService;
    @Mock private TrainingAccessService accessService;
    @Mock private CompanyScopeService companyScopeService;
    @Mock private ObjectProvider<ContentGenerationService> selfProvider;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID companyId = UUID.randomUUID();

    private ContentGenerationService service() {
        return new ContentGenerationService(chatStreamAdapter, promptLoader, objectMapper,
                knowledgeRepository, caseRepository, datasetRepository, itemRepository,
                runRepository, runItemRepository, jobRepository, signalService,
                accessService, companyScopeService, selfProvider);
    }

    @Test
    void persistCase_textProfileAndQuote_writtenAsValidJson() throws Exception {
        when(caseRepository.existsByCompanyScopeAndLowerTitle(any(), anyString())).thenReturn(false);
        when(caseRepository.save(any(IndustryCase.class))).thenAnswer(inv -> inv.getArgument(0));

        JsonNode draft = objectMapper.readTree("""
                {"title":"预算未批的推进案例","result":"win","confidence":0.9,
                 "customerProfile":"制造业/采购总监/预算未批",
                 "dialogueQuote":"我们再等等预算",
                 "objection":"预算没批","keyActions":"拆小单","reusablePoints":"先做试点"}
                """);

        IndustryCase saved = service().persistCase(UUID.randomUUID(), companyId, "预算异议",
                draft, List.of("客户说预算没批"));

        JsonNode profile = objectMapper.readTree(saved.getCustomerProfile());
        assertEquals("制造业/采购总监/预算未批", profile.path("描述").asText());
        JsonNode quotes = objectMapper.readTree(saved.getDialogueQuote());
        assertTrue(quotes.isArray());
        assertEquals("我们再等等预算", quotes.get(0).asText());
        assertEquals(IndustryCase.STATUS_PENDING, saved.getStatus());
    }

    @Test
    void persistEvalItems_skipsDuplicateAndSavesDraft() throws Exception {
        UUID datasetId = UUID.randomUUID();
        com.aiextract.model.EvaluationDataset dataset = com.aiextract.model.EvaluationDataset.builder()
                .id(datasetId).companyId(companyId).currentVersion(1).status("draft").build();
        when(itemRepository.findQuestionsByDatasetIdAndVersionAndStatusIn(eq(datasetId), eq(1), any()))
                .thenReturn(java.util.List.of("已有题"));
        when(itemRepository.findMaxOrderNo(datasetId, 1)).thenReturn(0);
        when(itemRepository.save(any(com.aiextract.model.EvaluationItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(itemRepository.countByDatasetIdAndVersionAndStatus(datasetId, 1,
                com.aiextract.model.EvaluationItem.STATUS_ACTIVE)).thenReturn(1L);
        when(datasetRepository.save(any(com.aiextract.model.EvaluationDataset.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        JsonNode items = objectMapper.readTree("""
                [{"question":"已有题","expectedPoints":["A"]},
                 {"question":"新题","expectedPoints":["B"]}]
                """);

        var saved = service().persistEvalItems(UUID.randomUUID(), companyId, dataset, "价格异议",
                items, List.of("客户嫌贵"), 5);

        assertEquals(1, saved.size());
        assertEquals("新题", saved.get(0).getQuestion());
        assertEquals(com.aiextract.model.EvaluationItem.STATUS_DRAFT, saved.get(0).getStatus());
    }

    @Test
    void persistCase_objectProfile_keptAsObject() throws Exception {
        when(caseRepository.existsByCompanyScopeAndLowerTitle(any(), anyString())).thenReturn(false);
        when(caseRepository.save(any(IndustryCase.class))).thenAnswer(inv -> inv.getArgument(0));

        JsonNode draft = objectMapper.readTree("""
                {"title":"结构化画像案例","result":"loss","confidence":0.8,
                 "customerProfile":{"行业":"精密制造","角色":"采购总监"},
                 "dialogueQuote":["价格太高","再比较一下"]}
                """);

        IndustryCase saved = service().persistCase(UUID.randomUUID(), companyId, "价格异议",
                draft, List.of("客户嫌贵"));

        JsonNode profile = objectMapper.readTree(saved.getCustomerProfile());
        assertEquals("精密制造", profile.path("行业").asText());
        JsonNode quotes = objectMapper.readTree(saved.getDialogueQuote());
        assertEquals(2, quotes.size());
    }
}
