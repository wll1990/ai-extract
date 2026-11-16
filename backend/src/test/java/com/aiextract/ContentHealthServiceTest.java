package com.aiextract;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.service.ContentHealthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 内容体检：过期、不可检索、重复标题、案例字段缺失。
 */
@ExtendWith(MockitoExtension.class)
class ContentHealthServiceTest {

    @Mock private KnowledgeEntryRepository knowledgeRepository;
    @Mock private IndustryCaseRepository caseRepository;
    @Mock private CompanyScopeService companyScopeService;

    private final UUID companyId = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();

    private ContentHealthService service() {
        return new ContentHealthService(knowledgeRepository, caseRepository, companyScopeService);
    }

    @Test
    void knowledge_expiredAndNotIndexed_flagged() {
        KnowledgeEntry k = KnowledgeEntry.builder()
                .id(id).companyId(companyId).title("价格异议处理")
                .content("很短的正文").category("price").status(KnowledgeEntry.STATUS_ACTIVE)
                .embeddingStatus("failed").source("《手册》")
                .expireAt(LocalDateTime.now().minusDays(1)).build();
        when(companyScopeService.requireKnowledgeReadable(id)).thenReturn(k);
        when(knowledgeRepository.findActiveByCompanyAndLowerTitle(eq(companyId), anyString()))
                .thenReturn(List.of(k));
        lenient().when(knowledgeRepository.findSimilarByCategory(eq(companyId), anyString(), eq(id), any()))
                .thenReturn(List.of());

        var view = service().knowledgeHealth(id);

        assertTrue(view.issues().stream().anyMatch(i -> "EXPIRED".equals(i.code())));
        assertTrue(view.issues().stream().anyMatch(i -> "NOT_SEARCHABLE".equals(i.code())));
        assertTrue(view.issues().stream().anyMatch(i -> "TOO_SHORT".equals(i.code())));
    }

    @Test
    void knowledge_duplicateTitle_flagged() {
        KnowledgeEntry k = KnowledgeEntry.builder()
                .id(id).companyId(companyId).title("竞品对比").content("x".repeat(120))
                .category("competitor").status(KnowledgeEntry.STATUS_PENDING).source("手册").build();
        KnowledgeEntry other = KnowledgeEntry.builder()
                .id(UUID.randomUUID()).companyId(companyId).title("竞品对比")
                .status(KnowledgeEntry.STATUS_ACTIVE).category("competitor").build();
        when(companyScopeService.requireKnowledgeReadable(id)).thenReturn(k);
        when(knowledgeRepository.findActiveByCompanyAndLowerTitle(eq(companyId), anyString()))
                .thenReturn(List.of(k, other));
        lenient().when(knowledgeRepository.findSimilarByCategory(eq(companyId), anyString(), eq(id), any()))
                .thenReturn(List.of());

        var view = service().knowledgeHealth(id);

        assertTrue(view.issues().stream().anyMatch(i -> "DUPLICATE_TITLE".equals(i.code())));
    }

    @Test
    void case_missingFields_flagged() {
        IndustryCase c = IndustryCase.builder()
                .id(id).companyId(companyId).title("某制造业赢单").status(IndustryCase.STATUS_PENDING)
                .result(IndustryCase.RESULT_WIN).build();
        when(companyScopeService.requireCaseReadable(id)).thenReturn(c);
        when(caseRepository.findActiveByCompanyAndLowerTitle(eq(companyId), anyString()))
                .thenReturn(List.of(c));
        lenient().when(caseRepository.findSimilarByScene(eq(companyId), anyString(), eq(id), any()))
                .thenReturn(List.of());

        var view = service().caseHealth(id);

        assertTrue(view.issues().stream().anyMatch(i -> "NO_SCENE".equals(i.code())));
        assertTrue(view.issues().stream().anyMatch(i -> "NO_ACTIONS".equals(i.code())));
        assertTrue(view.issues().stream().anyMatch(i -> "NO_REUSE".equals(i.code())));
    }
}
