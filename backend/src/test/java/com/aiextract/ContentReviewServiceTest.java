package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.ContentGenerationJobRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.service.ContentReviewService;
import com.aiextract.service.DatasetService;
import com.aiextract.service.KnowledgeCaseService;
import com.aiextract.service.TrainingAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI 草稿审核台：只列草稿、丢弃理由必填、跨企业与非 AI 条目被拒。 */
@ExtendWith(MockitoExtension.class)
class ContentReviewServiceTest {

    @Mock private KnowledgeEntryRepository knowledgeRepository;
    @Mock private IndustryCaseRepository caseRepository;
    @Mock private EvaluationItemRepository itemRepository;
    @Mock private ContentGenerationJobRepository jobRepository;
    @Mock private KnowledgeCaseService knowledgeCaseService;
    @Mock private DatasetService datasetService;
    @Mock private TrainingAccessService accessService;
    @Mock private CompanyScopeService companyScopeService;

    private ContentReviewService service;
    private final UUID companyId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ContentReviewService(knowledgeRepository, caseRepository, itemRepository,
                jobRepository, knowledgeCaseService, datasetService, accessService, companyScopeService);
        TokenContext.set(userId, companyId);
        org.mockito.Mockito.lenient().when(accessService.requireCompanyId()).thenReturn(companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void listEvalDrafts_usesDraftStatusAndCompanyScope() {
        EvaluationItem item = EvaluationItem.builder()
                .id(UUID.randomUUID()).companyId(companyId).datasetId(UUID.randomUUID())
                .question("价格异议怎么处理？").status(EvaluationItem.STATUS_DRAFT)
                .generated(true).generationJobId(UUID.randomUUID())
                .createdAt(LocalDateTime.now()).build();
        when(itemRepository.findByCompanyIdAndStatusAndGenerationJobIdIsNotNullOrderByCreatedAtDesc(
                eq(companyId), eq(EvaluationItem.STATUS_DRAFT), any()))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));
        when(jobRepository.findByCompanyIdAndIdIn(eq(companyId), anyList())).thenReturn(List.of());

        var result = service.list("eval_item", null, 1, 20);

        assertEquals("eval_item", result.kind());
        assertEquals(1, result.items().size());
        assertEquals("价格异议怎么处理？", result.items().get(0).title());
    }

    @Test
    void reject_withoutReason_isRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reject("knowledge", UUID.randomUUID(), "  "));
        assertEquals(400, ex.getErrorCode());
        verify(knowledgeCaseService, never()).deprecateKnowledge(any());
    }

    @Test
    void approveKnowledge_nonAiEntry_isRejected() {
        UUID id = UUID.randomUUID();
        KnowledgeEntry k = KnowledgeEntry.builder()
                .id(id).companyId(companyId).status(KnowledgeEntry.STATUS_PENDING)
                .title("人工录入").build();
        when(companyScopeService.requireKnowledgeReadable(id)).thenReturn(k);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.approveKnowledge(id, null));
        assertEquals(400, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("AI"));
    }

    @Test
    void approveEval_crossCompany_propagates404() {
        UUID id = UUID.randomUUID();
        EvaluationItem item = EvaluationItem.builder()
                .id(id).companyId(companyId).datasetId(UUID.randomUUID())
                .status(EvaluationItem.STATUS_DRAFT).generated(true)
                .generationJobId(UUID.randomUUID()).build();
        when(itemRepository.findById(id)).thenReturn(Optional.of(item));
        org.mockito.Mockito.doThrow(new BusinessException(404, "题集不存在或不可访问"))
                .when(companyScopeService).assertDatasetWritable(item.getDatasetId());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.approveEvalItem(id));
        assertEquals(404, ex.getErrorCode());
        verify(datasetService, never()).approveGeneratedItem(any());
    }
}
