package com.aiextract;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.AdminAuditLogRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.service.ContentDeletionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 彻底删除：状态、版本链、培训引用三道闸门。
 */
@ExtendWith(MockitoExtension.class)
class ContentDeletionServiceTest {

    @Mock private KnowledgeEntryRepository knowledgeRepository;
    @Mock private IndustryCaseRepository caseRepository;
    @Mock private TrainingTaskRepository trainingTaskRepository;
    @Mock private AdminAuditLogRepository auditLogRepository;

    @InjectMocks private ContentDeletionService service;

    private KnowledgeEntry entry(String status) {
        return KnowledgeEntry.builder()
                .id(UUID.randomUUID()).companyId(UUID.randomUUID())
                .title("测试知识").content("正文").category("methodology")
                .version(1).status(status).sourceType(KnowledgeEntry.SOURCE_TYPE_TENANT)
                .build();
    }

    @Test
    void activeEntry_cannotBeDeleted() {
        KnowledgeEntry k = entry(KnowledgeEntry.STATUS_ACTIVE);
        when(knowledgeRepository.findById(k.getId())).thenReturn(Optional.of(k));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.deleteKnowledge(k.getId(), UUID.randomUUID()));

        assertEquals(400, e.getErrorCode());
        verify(knowledgeRepository, never()).delete(any());
    }

    @Test
    void entryWithDerivedVersion_cannotBeDeleted() {
        KnowledgeEntry k = entry(KnowledgeEntry.STATUS_DEPRECATED);
        when(knowledgeRepository.findById(k.getId())).thenReturn(Optional.of(k));
        when(knowledgeRepository.existsBySupersedesId(k.getId())).thenReturn(true);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.deleteKnowledge(k.getId(), UUID.randomUUID()));

        assertEquals(409, e.getErrorCode());
        verify(knowledgeRepository, never()).delete(any());
    }

    @Test
    void entryReferencedByTraining_cannotBeDeleted() {
        KnowledgeEntry k = entry(KnowledgeEntry.STATUS_DRAFT);
        when(knowledgeRepository.findById(k.getId())).thenReturn(Optional.of(k));
        when(knowledgeRepository.existsBySupersedesId(k.getId())).thenReturn(false);
        when(trainingTaskRepository.existsByResourceId(k.getId())).thenReturn(true);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.deleteKnowledge(k.getId(), UUID.randomUUID()));

        assertEquals(409, e.getErrorCode());
        verify(knowledgeRepository, never()).delete(any());
    }

    @Test
    void draftEntryWithoutReferences_isDeleted() {
        KnowledgeEntry k = entry(KnowledgeEntry.STATUS_DRAFT);
        when(knowledgeRepository.findById(k.getId())).thenReturn(Optional.of(k));
        when(knowledgeRepository.existsBySupersedesId(k.getId())).thenReturn(false);
        when(trainingTaskRepository.existsByResourceId(k.getId())).thenReturn(false);

        service.deleteKnowledge(k.getId(), UUID.randomUUID());

        verify(knowledgeRepository).delete(k);
    }
}
