package com.aiextract.service;

import com.aiextract.repository.KnowledgeCaseRetrieveLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 知识/案例消费回流 —— 对话之外的评测与培训阅读也写引用日志。
 *
 * <p>写入失败只记 warn，不阻断答题/阅读：引用统计是旁路，不是主链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeUsageService {

    public static final String USAGE_EVALUATION = "evaluation";
    public static final String USAGE_TRAINING_READING = "training_reading";

    private final KnowledgeCaseRetrieveLogRepository repository;

    /** 评测答题：同一次运行题对同一条目只记一次。 */
    @Transactional(rollbackFor = Exception.class)
    public void logEvaluation(UUID companyId, UUID skillId, UUID runItemId,
                              KnowledgeCaseRetriever.KnowledgeCaseHits hits) {
        if (hits == null || (hits.knowledge().isEmpty() && hits.cases().isEmpty())) {
            return;
        }
        try {
            for (var k : hits.knowledge()) {
                insert(k.id(), null, skillId, companyId, USAGE_EVALUATION, runItemId,
                        k.similarity(), "eval:" + runItemId + ":" + k.id());
            }
            for (var c : hits.cases()) {
                insert(null, c.id(), skillId, companyId, USAGE_EVALUATION, runItemId,
                        c.similarity(), "eval:" + runItemId + ":" + c.id());
            }
        } catch (Exception e) {
            log.warn("评测消费回流写入失败 runItemId={}: {}", runItemId, e.getMessage());
        }
    }

    /** 培训阅读：同一人同一天打开同一任务只记一次。 */
    @Transactional(rollbackFor = Exception.class)
    public void logTrainingReading(UUID companyId, UUID assignmentId, UUID taskId, UUID userId,
                                   UUID recordId, UUID resourceId, boolean isCase, Double similarity) {
        if (resourceId == null) {
            return;
        }
        try {
            String dedupe = "training:" + assignmentId + ":" + taskId + ":" + userId + ":"
                    + LocalDate.now();
            insert(isCase ? null : resourceId, isCase ? resourceId : null, null, companyId,
                    USAGE_TRAINING_READING, recordId, similarity, dedupe);
        } catch (Exception e) {
            log.warn("培训阅读消费回流写入失败 taskId={}: {}", taskId, e.getMessage());
        }
    }

    private void insert(UUID knowledgeId, UUID caseId, UUID skillId, UUID companyId,
                        String usageType, UUID usageRefId, Double similarity, String dedupeKey) {
        repository.insertIgnore(UUID.randomUUID(), knowledgeId, caseId, skillId, companyId,
                usageType, usageRefId, similarity, dedupeKey, LocalDateTime.now());
    }
}
