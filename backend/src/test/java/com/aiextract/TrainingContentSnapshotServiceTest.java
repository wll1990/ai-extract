package com.aiextract;

import com.aiextract.model.KnowledgeEntry;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.service.TrainingContentSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrainingContentSnapshotServiceTest {

    private final KnowledgeEntryRepository knowledgeRepository = mock(KnowledgeEntryRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TrainingContentSnapshotService service = new TrainingContentSnapshotService(
            knowledgeRepository, mock(IndustryCaseRepository.class), objectMapper);

    @Test
    void publishFreezesReadingContent() {
        UUID knowledgeId = UUID.randomUUID();
        KnowledgeEntry entry = KnowledgeEntry.builder().id(knowledgeId)
                .title("三年总拥有成本").content("采购价+运维+迁移").build();
        when(knowledgeRepository.findById(knowledgeId)).thenReturn(Optional.of(entry));
        TrainingTask task = TrainingTask.builder().id(UUID.randomUUID())
                .type(TrainingTask.TYPE_READING)
                .resourceType(TrainingTask.RESOURCE_KNOWLEDGE)
                .resourceId(knowledgeId).title("阅读：TCO").build();

        service.captureForPublish(List.of(task), entry.getCompanyId());

        assertNotNull(task.getResourceSnapshot());
        assertEquals(1, task.getSnapshotVersion());
        Map<String, Object> snapshot = service.read(task);
        assertEquals("三年总拥有成本", snapshot.get("title"));
        assertTrue(String.valueOf(snapshot.get("content")).contains("运维"));
    }

    @Test
    void rejectsCrossCompanyResource() {
        UUID knowledgeId = UUID.randomUUID();
        UUID ownerCompany = UUID.randomUUID();
        UUID planCompany = UUID.randomUUID();
        KnowledgeEntry entry = KnowledgeEntry.builder().id(knowledgeId)
                .companyId(ownerCompany).title("别家知识").content("x").build();
        when(knowledgeRepository.findById(knowledgeId)).thenReturn(Optional.of(entry));
        TrainingTask task = TrainingTask.builder().id(UUID.randomUUID())
                .type(TrainingTask.TYPE_READING)
                .resourceType(TrainingTask.RESOURCE_KNOWLEDGE)
                .resourceId(knowledgeId).title("阅读").build();

        org.junit.jupiter.api.Assertions.assertThrows(
                com.aiextract.exception.BusinessException.class,
                () -> service.captureForPublish(List.of(task), planCompany));
    }

    @Test
    void readReturnsNullWhenNoSnapshot() {
        TrainingTask task = TrainingTask.builder().id(UUID.randomUUID())
                .type(TrainingTask.TYPE_READING).build();
        assertEquals(null, service.read(task));
    }
}
