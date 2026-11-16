package com.aiextract.service;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.AdminAuditLog;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.AdminAuditLogRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.TrainingTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 知识/案例彻底删除 —— 与「下架（deprecated）」区分。
 *
 * <p>下架可恢复，用于临时停止引用；彻底删除只允许未生效或已下架的条目，
 * 并且必须没有派生版本、没有被培训任务引用，避免版本链断裂和学习任务失效。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentDeletionService {

    /** 只用 Jackson 生成 JSON：手拼字符串遇到换行/控制字符会写出非法 jsonb */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;
    private final TrainingTaskRepository trainingTaskRepository;
    private final AdminAuditLogRepository auditLogRepository;

    @Transactional(rollbackFor = Exception.class)
    public void deleteKnowledge(UUID id, UUID operatorId) {
        KnowledgeEntry k = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在"));
        assertDeletable("知识", k.getStatus(),
                knowledgeRepository.existsBySupersedesId(id),
                trainingTaskRepository.existsByResourceId(id));
        writeAudit("delete_knowledge", "knowledge", id, operatorId, k.getTitle());
        knowledgeRepository.delete(k);
        log.info("知识已彻底删除 id={} title={}", id, k.getTitle());
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteCase(UUID id, UUID operatorId) {
        IndustryCase c = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "案例不存在"));
        assertDeletable("案例", c.getStatus(),
                caseRepository.existsBySupersedesId(id),
                trainingTaskRepository.existsByResourceId(id));
        writeAudit("delete_case", "case", id, operatorId, c.getTitle());
        caseRepository.delete(c);
        log.info("案例已彻底删除 id={} title={}", id, c.getTitle());
    }

    private void assertDeletable(String label, String status, boolean hasChildren, boolean referencedByTraining) {
        if (KnowledgeEntry.STATUS_ACTIVE.equals(status)) {
            throw new BusinessException(400, "这条" + label + "已生效，请先「下架」再彻底删除");
        }
        if (!Set.of(KnowledgeEntry.STATUS_DRAFT, KnowledgeEntry.STATUS_PENDING,
                KnowledgeEntry.STATUS_DEPRECATED).contains(status)) {
            throw new BusinessException(400, "当前状态不允许删除");
        }
        if (hasChildren) {
            throw new BusinessException(409, "这条" + label + "还有派生版本，删除会让版本链断裂；请先处理派生版本");
        }
        if (referencedByTraining) {
            throw new BusinessException(409, "有培训任务引用这条" + label + "，删除会导致学习任务失效；请先调整培训计划");
        }
    }

    private void writeAudit(String action, String targetType, UUID targetId, UUID operatorId, String title) {
        try {
            auditLogRepository.save(AdminAuditLog.builder()
                    .id(UUID.randomUUID())
                    .adminId(operatorId != null ? operatorId
                            : UUID.fromString("00000000-0000-0000-0000-000000000000"))
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(JSON.createObjectNode().put("title", title).toString())
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.debug("删除审计写入失败: {}", e.getMessage());
        }
    }
}
