package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ContentGenerationJob;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.ContentGenerationJobRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AI 草稿审核台 —— 知识 / 案例 / 考题统一入口。
 *
 * <p>只列带 generation_job_id 的草稿，行内带证据与生成任务摘要；
 * 通过/丢弃复用原有审核链路，审核人/时间/结论落在各实体 reviewed_* 字段。</p>
 */
@Service
@RequiredArgsConstructor
public class ContentReviewService {

    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;
    private final EvaluationItemRepository itemRepository;
    private final ContentGenerationJobRepository jobRepository;
    private final KnowledgeCaseService knowledgeCaseService;
    private final DatasetService datasetService;
    private final TrainingAccessService accessService;
    private final CompanyScopeService companyScopeService;

    public record JobSummary(UUID id, String kind, String status, int producedCount,
                             String errorMessage, LocalDateTime createdAt, LocalDateTime finishedAt) {
    }

    public record DraftView(String kind, UUID id, String title, String status, String sceneTag,
                            UUID datasetId, String evidence, UUID generationJobId,
                            JobSummary job, LocalDateTime createdAt, boolean canApprove) {
    }

    public record DraftList(String kind, long total, int page, int size, List<DraftView> items) {
    }

    // ==================== 列表 ====================

    public DraftList list(String kind, UUID datasetId, int page, int size) {
        UUID companyId = accessService.requireCompanyId();
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(1, Math.min(size, 100));
        PageRequest pageable = PageRequest.of(safePage - 1, safeSize);
        String normalized = normalizeKind(kind);

        if ("eval_item".equals(normalized)) {
            Page<EvaluationItem> items = datasetId == null
                    ? itemRepository.findByCompanyIdAndStatusAndGenerationJobIdIsNotNullOrderByCreatedAtDesc(
                            companyId, EvaluationItem.STATUS_DRAFT, pageable)
                    : itemRepository.findByCompanyIdAndDatasetIdAndStatusAndGenerationJobIdIsNotNullOrderByCreatedAtDesc(
                            companyId, datasetId, EvaluationItem.STATUS_DRAFT, pageable);
            Map<UUID, JobSummary> jobs = jobsById(companyId,
                    items.getContent().stream().map(EvaluationItem::getGenerationJobId).toList());
            List<DraftView> views = items.getContent().stream()
                    .map(i -> new DraftView("eval_item", i.getId(), i.getQuestion(), i.getStatus(),
                            i.getSceneTag(), i.getDatasetId(), i.getEvidence(), i.getGenerationJobId(),
                            jobs.get(i.getGenerationJobId()), i.getCreatedAt(), true))
                    .toList();
            return new DraftList("eval_item", items.getTotalElements(), safePage, safeSize, views);
        }
        if ("case".equals(normalized)) {
            Page<IndustryCase> items = caseRepository
                    .findByCompanyIdAndStatusAndGenerationJobIdIsNotNullOrderByCreatedAtDesc(
                            companyId, IndustryCase.STATUS_PENDING, pageable);
            Map<UUID, JobSummary> jobs = jobsById(companyId,
                    items.getContent().stream().map(IndustryCase::getGenerationJobId).toList());
            List<DraftView> views = items.getContent().stream()
                    .map(c -> new DraftView("case", c.getId(), c.getTitle(), c.getStatus(),
                            c.getSceneTag(), null, c.getEvidence(), c.getGenerationJobId(),
                            jobs.get(c.getGenerationJobId()), c.getCreatedAt(), true))
                    .toList();
            return new DraftList("case", items.getTotalElements(), safePage, safeSize, views);
        }
        Page<KnowledgeEntry> items = knowledgeRepository
                .findByCompanyIdAndStatusAndGenerationJobIdIsNotNullOrderByCreatedAtDesc(
                        companyId, KnowledgeEntry.STATUS_PENDING, pageable);
        Map<UUID, JobSummary> jobs = jobsById(companyId,
                items.getContent().stream().map(KnowledgeEntry::getGenerationJobId).toList());
        List<DraftView> views = items.getContent().stream()
                .map(k -> new DraftView("knowledge", k.getId(), k.getTitle(), k.getStatus(),
                        null, null, k.getEvidence(), k.getGenerationJobId(),
                        jobs.get(k.getGenerationJobId()), k.getCreatedAt(), true))
                .toList();
        return new DraftList("knowledge", items.getTotalElements(), safePage, safeSize, views);
    }

    // ==================== 审核动作 ====================

    @Transactional(rollbackFor = Exception.class)
    public void approveKnowledge(UUID id, LocalDateTime expireAt) {
        requireKnowledgeDraft(id);
        // 审核台给出默认有效期，避免「失效时间必填」把 AI 草稿卡死
        LocalDateTime expire = expireAt != null ? expireAt : LocalDateTime.now().plusYears(1);
        knowledgeCaseService.approveKnowledge(id, expire);
        markKnowledgeReviewed(id, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void approveCase(UUID id, LocalDateTime expireAt) {
        requireCaseDraft(id);
        knowledgeCaseService.approveCase(id, expireAt);
        markCaseReviewed(id, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void approveEvalItem(UUID id) {
        EvaluationItem item = requireEvalDraft(id);
        companyScopeService.assertDatasetWritable(item.getDatasetId());
        datasetService.approveGeneratedItem(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(String kind, UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(400, "丢弃草稿必须填写理由");
        }
        String normalized = normalizeKind(kind);
        if ("eval_item".equals(normalized)) {
            EvaluationItem item = requireEvalDraft(id);
            companyScopeService.assertDatasetWritable(item.getDatasetId());
            datasetService.rejectGeneratedItem(id, reason);
            return;
        }
        if ("case".equals(normalized)) {
            requireCaseDraft(id);
            knowledgeCaseService.deprecateCase(id);
            markCaseReviewed(id, reason.trim());
            return;
        }
        requireKnowledgeDraft(id);
        knowledgeCaseService.deprecateKnowledge(id);
        markKnowledgeReviewed(id, reason.trim());
    }

    // ==================== 内部 ====================

    private KnowledgeEntry requireKnowledgeDraft(UUID id) {
        KnowledgeEntry k = companyScopeService.requireKnowledgeReadable(id);
        if (k.getGenerationJobId() == null) {
            throw new BusinessException(400, "该条目不是 AI 生成的草稿");
        }
        if (!KnowledgeEntry.STATUS_PENDING.equals(k.getStatus())) {
            throw new BusinessException(400, "该草稿已处理或不是待审核状态");
        }
        return k;
    }

    private IndustryCase requireCaseDraft(UUID id) {
        IndustryCase c = companyScopeService.requireCaseReadable(id);
        if (c.getGenerationJobId() == null) {
            throw new BusinessException(400, "该案例不是 AI 生成的草稿");
        }
        if (!IndustryCase.STATUS_PENDING.equals(c.getStatus())) {
            throw new BusinessException(400, "该草稿已处理或不是待审核状态");
        }
        return c;
    }

    private EvaluationItem requireEvalDraft(UUID id) {
        EvaluationItem item = itemRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "题目不存在"));
        if (!Boolean.TRUE.equals(item.getGenerated()) || item.getGenerationJobId() == null) {
            throw new BusinessException(400, "该题目不是 AI 生成的草稿");
        }
        if (!EvaluationItem.STATUS_DRAFT.equals(item.getStatus())) {
            throw new BusinessException(400, "该题目已处理或不是待审核状态");
        }
        return item;
    }

    private void markKnowledgeReviewed(UUID id, String note) {
        knowledgeRepository.findById(id).ifPresent(k -> {
            k.setReviewedBy(TokenContext.get());
            k.setReviewedAt(LocalDateTime.now());
            if (note != null) {
                k.setReviewNote(note);
            }
            knowledgeRepository.save(k);
        });
    }

    private void markCaseReviewed(UUID id, String note) {
        caseRepository.findById(id).ifPresent(c -> {
            c.setReviewedBy(TokenContext.get());
            c.setReviewedAt(LocalDateTime.now());
            if (note != null) {
                c.setReviewNote(note);
            }
            caseRepository.save(c);
        });
    }

    private Map<UUID, JobSummary> jobsById(UUID companyId, List<UUID> ids) {
        List<UUID> unique = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (unique.isEmpty()) {
            return Map.of();
        }
        Map<UUID, JobSummary> result = new HashMap<>();
        for (ContentGenerationJob job : jobRepository.findByCompanyIdAndIdIn(companyId, unique)) {
            result.put(job.getId(), new JobSummary(job.getId(), job.getKind(), job.getStatus(),
                    job.getProducedCount() == null ? 0 : job.getProducedCount(),
                    job.getErrorMessage(), job.getCreatedAt(), job.getFinishedAt()));
        }
        return result;
    }

    private String normalizeKind(String kind) {
        if (kind == null || kind.isBlank() || "knowledge".equals(kind)) {
            return "knowledge";
        }
        if ("case".equals(kind)) {
            return kind;
        }
        if ("eval_item".equals(kind) || "eval-item".equals(kind)) {
            return "eval_item";
        }
        throw new BusinessException(400, "kind 只能是 knowledge / case / eval_item");
    }
}
