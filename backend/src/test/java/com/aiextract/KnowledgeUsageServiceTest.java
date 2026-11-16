package com.aiextract;

import com.aiextract.repository.KnowledgeCaseRetrieveLogRepository;
import com.aiextract.service.KnowledgeCaseRetriever;
import com.aiextract.service.KnowledgeUsageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 消费回流：评测按 runItem+条目去重；培训阅读按 assignment+task+user+day 去重。 */
@ExtendWith(MockitoExtension.class)
class KnowledgeUsageServiceTest {

    @Mock private KnowledgeCaseRetrieveLogRepository repository;

    private final UUID companyId = UUID.randomUUID();
    private final UUID skillId = UUID.randomUUID();
    private final UUID runItemId = UUID.randomUUID();
    private final UUID knowledgeId = UUID.randomUUID();

    @Test
    void logEvaluation_writesDedupeKeyPerRunItemAndEntry() {
        when(repository.insertIgnore(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        var hits = new KnowledgeCaseRetriever.KnowledgeCaseHits(
                List.of(new KnowledgeCaseRetriever.KnowledgeHit(knowledgeId, "标题", "正文",
                        "price", "手册", 0.82)),
                List.of());

        new KnowledgeUsageService(repository).logEvaluation(companyId, skillId, runItemId, hits);

        verify(repository).insertIgnore(any(UUID.class), eq(knowledgeId), isNull(), eq(skillId),
                eq(companyId), eq(KnowledgeUsageService.USAGE_EVALUATION), eq(runItemId),
                eq(0.82), eq("eval:" + runItemId + ":" + knowledgeId), any(LocalDateTime.class));
    }

    @Test
    void logTrainingReading_usesDayLevelDedupe() {
        UUID assignmentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();

        new KnowledgeUsageService(repository).logTrainingReading(companyId, assignmentId, taskId,
                userId, recordId, knowledgeId, false, null);

        verify(repository).insertIgnore(any(UUID.class), eq(knowledgeId), isNull(), isNull(),
                eq(companyId), eq(KnowledgeUsageService.USAGE_TRAINING_READING), eq(recordId),
                isNull(), eq("training:" + assignmentId + ":" + taskId + ":" + userId + ":"
                        + java.time.LocalDate.now()), any(LocalDateTime.class));
    }
}
