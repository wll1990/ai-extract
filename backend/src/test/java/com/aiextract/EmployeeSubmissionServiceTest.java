package com.aiextract;

import com.aiextract.config.TokenContext;
import com.aiextract.dto.KnowledgeEntryRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.service.EmployeeSubmissionService;
import com.aiextract.service.KnowledgeCaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmployeeSubmissionServiceTest {

    private KnowledgeCaseService knowledgeCaseService;
    private KnowledgeEntryRepository knowledgeRepository;
    private EmployeeSubmissionService service;

    private final UUID companyId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        knowledgeCaseService = mock(KnowledgeCaseService.class);
        knowledgeRepository = mock(KnowledgeEntryRepository.class);
        service = new EmployeeSubmissionService(knowledgeCaseService, knowledgeRepository,
                mock(IndustryCaseRepository.class));
        TokenContext.set(userId, companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void submitForcesPendingTenantAndOwner() {
        KnowledgeEntry saved = KnowledgeEntry.builder().id(UUID.randomUUID())
                .companyId(companyId).title("t").status(KnowledgeEntry.STATUS_PENDING).build();
        when(knowledgeCaseService.createKnowledge(any(), eq(companyId))).thenReturn(saved);

        KnowledgeEntryRequest req = KnowledgeEntryRequest.builder()
                .title("客户问三年总拥有成本怎么算")
                .category("methodology")
                .sourceType("public")
                .status("active")
                .content("按采购价、运维费、迁移成本三年加总。")
                .build();

        service.submitKnowledge(req);

        ArgumentCaptor<KnowledgeEntryRequest> captor =
                ArgumentCaptor.forClass(KnowledgeEntryRequest.class);
        verify(knowledgeCaseService).createKnowledge(captor.capture(), eq(companyId));
        assertEquals(KnowledgeEntry.STATUS_PENDING, captor.getValue().getStatus());
        assertEquals(KnowledgeEntry.SOURCE_TYPE_TENANT, captor.getValue().getSourceType());
        assertNotNull(captor.getValue().getExpireAt());
    }

    @Test
    void withdrawRejectsOtherUsersSubmission() {
        UUID id = UUID.randomUUID();
        KnowledgeEntry entry = KnowledgeEntry.builder().id(id).companyId(companyId)
                .createdBy(UUID.randomUUID()).status(KnowledgeEntry.STATUS_PENDING).build();
        when(knowledgeRepository.findById(id)).thenReturn(Optional.of(entry));

        assertThrows(BusinessException.class, () -> service.withdrawKnowledge(id));
    }

    @Test
    void withdrawOwnPendingReturnsDraft() {
        UUID id = UUID.randomUUID();
        KnowledgeEntry entry = KnowledgeEntry.builder().id(id).companyId(companyId)
                .createdBy(userId).status(KnowledgeEntry.STATUS_PENDING).build();
        when(knowledgeRepository.findById(id)).thenReturn(Optional.of(entry));
        when(knowledgeRepository.save(entry)).thenReturn(entry);

        KnowledgeEntry result = service.withdrawKnowledge(id);

        assertEquals(KnowledgeEntry.STATUS_DRAFT, result.getStatus());
    }
}
