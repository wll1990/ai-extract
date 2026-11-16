package com.aiextract.scheduler;

import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.service.KnowledgeCaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 知识/案例向量化失败重试。
 *
 * <p>审批采用两阶段：新版先 active，向量化成功后才下线旧版；失败时旧版继续生效。
 * 本调度按退避重试失败条目，成功后由 KnowledgeCaseService.finalize* 完成版本收口。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentIndexRetryScheduler {

    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;
    private final KnowledgeCaseService knowledgeCaseService;

    @Value("${app.content.index-retry.enabled:true}")
    private boolean enabled;

    @Value("${app.content.index-retry.batch-size:20}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.content.index-retry.interval-ms:60000}",
            initialDelayString = "${app.content.index-retry.initial-delay-ms:60000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        try {
            List<UUID> knowledgeIds = knowledgeRepository.findEmbeddingRetryIds(
                    now, PageRequest.of(0, Math.max(1, batchSize)));
            for (UUID id : knowledgeIds) {
                knowledgeCaseService.reEmbedKnowledgeById(id);
            }
            List<UUID> caseIds = caseRepository.findEmbeddingRetryIds(
                    now, PageRequest.of(0, Math.max(1, batchSize)));
            for (UUID id : caseIds) {
                knowledgeCaseService.reEmbedCaseById(id);
            }
            if (!knowledgeIds.isEmpty() || !caseIds.isEmpty()) {
                log.info("向量化重试完成 knowledge={} case={}", knowledgeIds.size(), caseIds.size());
            }
        } catch (Exception e) {
            log.warn("向量化重试调度失败: {}", e.getMessage(), e);
        }
    }
}
