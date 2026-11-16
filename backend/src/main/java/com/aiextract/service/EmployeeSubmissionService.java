package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.dto.IndustryCaseRequest;
import com.aiextract.dto.KnowledgeEntryRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 一线销售提交知识/案例草稿。
 *
 * <p>服务端强制：本企业、本人、pending、tenant；请求体里的 status/companyId/sourceType 一律忽略。
 * 审批仍需 KNOWLEDGE_MANAGE，员工只能撤回自己未审批的提交。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeSubmissionService {

    private final KnowledgeCaseService knowledgeCaseService;
    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;

    public KnowledgeEntry submitKnowledge(KnowledgeEntryRequest req) {
        UUID companyId = requireCompanyId();
        KnowledgeEntryRequest forced = KnowledgeEntryRequest.builder()
                .title(req.getTitle())
                .category(req.getCategory())
                .sourceType(KnowledgeEntry.SOURCE_TYPE_TENANT)
                .content(req.getContent())
                .expireAt(LocalDateTime.now().plusYears(1))
                .source(req.getSource())
                .status(KnowledgeEntry.STATUS_PENDING)
                .build();
        return knowledgeCaseService.createKnowledge(forced, companyId);
    }

    public IndustryCase submitCase(IndustryCaseRequest req) {
        UUID companyId = requireCompanyId();
        IndustryCaseRequest forced = IndustryCaseRequest.builder()
                .title(req.getTitle())
                .customerProfile(req.getCustomerProfile())
                .funnelStage(req.getFunnelStage())
                .sceneTag(req.getSceneTag())
                .dialogueQuote(req.getDialogueQuote())
                .objection(req.getObjection())
                .emotionChange(req.getEmotionChange())
                .keyActions(req.getKeyActions())
                .scripts(req.getScripts())
                .advanceNodes(req.getAdvanceNodes())
                .result(req.getResult())
                .reusablePoints(req.getReusablePoints())
                .failurePoints(req.getFailurePoints())
                .source(req.getSource())
                .expireAt(LocalDateTime.now().plusYears(1))
                .status(IndustryCase.STATUS_PENDING)
                .build();
        return knowledgeCaseService.createCase(forced, companyId);
    }

    public Page<KnowledgeEntry> myKnowledge(int page, int size) {
        return knowledgeRepository.findByCompanyIdAndCreatedByOrderByCreatedAtDesc(
                requireCompanyId(), requireUserId(), pageable(page, size));
    }

    public Page<IndustryCase> myCases(int page, int size) {
        return caseRepository.findByCompanyIdAndContributorIdOrderByCreatedAtDesc(
                requireCompanyId(), requireUserId(), pageable(page, size));
    }

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeEntry withdrawKnowledge(UUID id) {
        KnowledgeEntry entry = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "提交不存在"));
        assertOwn(entry.getCompanyId(), entry.getCreatedBy());
        if (!KnowledgeEntry.STATUS_PENDING.equals(entry.getStatus())
                && !KnowledgeEntry.STATUS_DRAFT.equals(entry.getStatus())) {
            throw new BusinessException(409, "已审批的内容不能撤回，请联系管理员下架");
        }
        entry.setStatus(KnowledgeEntry.STATUS_DRAFT);
        entry.setUpdatedAt(LocalDateTime.now());
        return knowledgeRepository.save(entry);
    }

    @Transactional(rollbackFor = Exception.class)
    public IndustryCase withdrawCase(UUID id) {
        IndustryCase entry = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "提交不存在"));
        assertOwn(entry.getCompanyId(), entry.getContributorId());
        if (!IndustryCase.STATUS_PENDING.equals(entry.getStatus())
                && !IndustryCase.STATUS_DRAFT.equals(entry.getStatus())) {
            throw new BusinessException(409, "已审批的内容不能撤回，请联系管理员下架");
        }
        entry.setStatus(IndustryCase.STATUS_DRAFT);
        entry.setUpdatedAt(LocalDateTime.now());
        return caseRepository.save(entry);
    }

    private void assertOwn(UUID companyId, UUID ownerId) {
        if (!requireCompanyId().equals(companyId) || !requireUserId().equals(ownerId)) {
            throw new BusinessException(404, "提交不存在");
        }
    }

    private UUID requireCompanyId() {
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            throw new BusinessException(403, "无企业归属，不能提交知识/案例");
        }
        return companyId;
    }

    private UUID requireUserId() {
        UUID userId = TokenContext.get();
        if (userId == null) {
            throw new BusinessException(403, "登录状态无效");
        }
        return userId;
    }

    private Pageable pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 50));
    }
}
