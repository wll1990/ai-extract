package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.DatasetReviewRequest;
import com.aiextract.dto.EvaluationDatasetRequest;
import com.aiextract.dto.EvaluationGenerationRequest;
import com.aiextract.dto.EvaluationItemRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationDataset;
import com.aiextract.model.EvaluationGenerationJob;
import com.aiextract.model.EvaluationItem;
import com.aiextract.repository.EvaluationDatasetRepository;
import com.aiextract.repository.EvaluationGenerationJobRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.DatasetService;
import com.aiextract.service.EvaluationGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 评测中心题集管理 Controller —— 只做参数校验、权限断言和路由，业务在 DatasetService。
 *
 * <p>列表按角色分支：super_admin 看全部，company_admin 看「本企业 + 公共模板」。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/admin/evaluations")
@RequiredArgsConstructor
public class AdminEvaluationController {

    private final DatasetService datasetService;
    private final EvaluationGenerationService generationService;
    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationItemRepository itemRepository;
    private final EvaluationGenerationJobRepository generationJobRepository;
    private final com.aiextract.repository.EvaluationDatasetVersionRepository versionRepository;
    private final UserRepository userRepository;
    private final CompanyScopeService companyScopeService;

    // ==================== 题集 ====================

    @GetMapping("/datasets")
    public ApiResponse<Map<String, Object>> listDatasets(
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        boolean scopeAll = companyScopeService.isUnrestrictedDataScope();
        UUID companyId = TokenContext.getCompanyId();
        Page<EvaluationDataset> p = datasetRepository.search(
                scopeAll, companyId, purpose, status, keyword, PageRequest.of(page - 1, size));
        Map<UUID, String> creatorNames = resolveCreatorNames(p.getContent().stream()
                .map(EvaluationDataset::getCreatedBy).filter(Objects::nonNull).toList());
        var content = p.getContent().stream().map(d -> {
            Map<String, Object> m = toDatasetMap(d);
            m.put("createdByName", d.getCreatedBy() != null ? creatorNames.get(d.getCreatedBy()) : null);
            return m;
        }).toList();
        return ApiResponse.success(PageResponse.of(content, p, page, size));
    }

    @PostMapping("/datasets")
    public ApiResponse<Map<String, Object>> createDataset(@Valid @RequestBody EvaluationDatasetRequest req) {
        UUID companyId = companyScopeService.isUnrestrictedDataScope() ? null : TokenContext.getCompanyId();
        EvaluationDataset ds = datasetService.createDataset(req, companyId);
        return ApiResponse.success(toDatasetMap(ds));
    }

    @GetMapping("/datasets/{id}")
    public ApiResponse<Map<String, Object>> getDataset(@PathVariable UUID id) {
        companyScopeService.assertDatasetReadable(id);
        EvaluationDataset ds = datasetRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "题集不存在"));
        return ApiResponse.success(toDatasetMap(ds));
    }

    @PutMapping("/datasets/{id}")
    public ApiResponse<Map<String, Object>> updateDataset(@PathVariable UUID id,
                                                          @Valid @RequestBody EvaluationDatasetRequest req) {
        companyScopeService.assertDatasetWritable(id);
        EvaluationDataset ds = datasetService.updateDataset(id, req);
        return ApiResponse.success(toDatasetMap(ds));
    }

    @PostMapping("/datasets/{id}/submit")
    public ApiResponse<Map<String, Object>> submitDataset(@PathVariable UUID id) {
        companyScopeService.assertDatasetWritable(id);
        EvaluationDataset ds = datasetService.submitForReview(id);
        return ApiResponse.success(toDatasetMap(ds));
    }

    @PostMapping("/datasets/{id}/review")
    public ApiResponse<Map<String, Object>> reviewDataset(@PathVariable UUID id,
                                                          @Valid @RequestBody DatasetReviewRequest req) {
        companyScopeService.assertDatasetReadable(id);
        EvaluationDataset ds = datasetService.reviewDataset(id, req.getAction(), req.getReason());
        return ApiResponse.success(toDatasetMap(ds));
    }

    @PostMapping("/datasets/{id}/publish")
    public ApiResponse<Map<String, Object>> publishDataset(@PathVariable UUID id) {
        companyScopeService.assertDatasetWritable(id);
        EvaluationDataset ds = datasetService.publishDataset(id);
        return ApiResponse.success(toDatasetMap(ds));
    }

    /** 版本列表：发布新版本后仍能回看旧发布版的题目 */
    @GetMapping("/datasets/{id}/versions")
    public ApiResponse<List<Map<String, Object>>> listVersions(@PathVariable UUID id) {
        companyScopeService.assertDatasetReadable(id);
        return ApiResponse.success(versionRepository.findByDatasetIdOrderByVersionDesc(id).stream()
                .map(v -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("version", v.getVersion());
                    m.put("status", v.getStatus());
                    m.put("itemCount", v.getItemCount());
                    m.put("frozenAt", v.getFrozenAt());
                    return m;
                })
                .toList());
    }

    @PostMapping("/datasets/{id}/versions")
    public ApiResponse<Map<String, Object>> createVersion(@PathVariable UUID id) {
        companyScopeService.assertDatasetWritable(id);
        EvaluationDataset ds = datasetService.createVersion(id);
        return ApiResponse.success(toDatasetMap(ds));
    }

    /**
     * 复制平台公共模板。
     *
     * <p>目标企业口径与「建题集」保持一致：super_admin → null（复制出的是新的平台模板），
     * 企业管理员 → 本企业。super_admin 若想把模板落到某企业，应改用企业管理员账号操作。</p>
     */
    @PostMapping("/datasets/{id}/copy")
    public ApiResponse<Map<String, Object>> copyPublicDataset(@PathVariable UUID id) {
        companyScopeService.assertDatasetReadable(id);
        UUID companyId = companyScopeService.isUnrestrictedDataScope() ? null : TokenContext.getCompanyId();
        EvaluationDataset copy = datasetService.copyPublicDataset(id, companyId);
        return ApiResponse.success(toDatasetMap(copy));
    }

    // ==================== 生成候选 ====================

    @PostMapping("/datasets/{id}/generation-jobs")
    public ApiResponse<Map<String, Object>> createGenerationJob(
            @PathVariable UUID id,
            @Valid @RequestBody EvaluationGenerationRequest req) {
        companyScopeService.assertDatasetWritable(id);
        assertSourcesReadable(req.getSourceType(), req.getSourceIds());
        UUID jobId = generationService.createJob(id, req.getSourceType(), req.getSourceIds());
        generationService.runGeneration(jobId, req.getSourceType(), req.getSourceIds());
        return ApiResponse.success(Map.of("jobId", jobId.toString()));
    }

    @GetMapping("/generation-jobs/{id}")
    public ApiResponse<Map<String, Object>> getGenerationJob(@PathVariable UUID id) {
        EvaluationGenerationJob job = generationJobRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "生成任务不存在"));
        companyScopeService.assertDatasetReadable(job.getDatasetId());
        return ApiResponse.success(toGenerationJobMap(job));
    }

    /** 题集最近的生成任务：离开页面后回来仍能看到进度与产出统计 */
    @GetMapping("/datasets/{id}/generation-jobs")
    public ApiResponse<List<Map<String, Object>>> listGenerationJobs(@PathVariable UUID id) {
        companyScopeService.assertDatasetReadable(id);
        return ApiResponse.success(generationJobRepository
                .findTop20ByDatasetIdOrderByCreatedAtDesc(id).stream()
                .map(this::toGenerationJobMap)
                .toList());
    }

    private Map<String, Object> toGenerationJobMap(EvaluationGenerationJob job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", job.getId().toString());
        m.put("datasetId", job.getDatasetId().toString());
        m.put("sourceType", job.getSourceType());
        m.put("status", job.getStatus());
        m.put("producedCount", job.getProducedCount());
        m.put("skippedCount", job.getSkippedCount());
        m.put("rejectedCount", job.getRejectedCount());
        m.put("errorMessage", job.getErrorMessage());
        m.put("createdAt", job.getCreatedAt());
        m.put("updatedAt", job.getUpdatedAt());
        return m;
    }

    // ==================== 题目 ====================

    @GetMapping("/datasets/{id}/items")
    public ApiResponse<Map<String, Object>> listItems(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer version,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        companyScopeService.assertDatasetReadable(id);
        EvaluationDataset ds = datasetRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "题集不存在"));
        int ver = version != null ? version : ds.getCurrentVersion();
        Page<EvaluationItem> p = itemRepository.findByDatasetIdAndVersionOrderByOrderNoAsc(
                id, ver, PageRequest.of(page - 1, size));
        return ApiResponse.success(PageResponse.of(p.getContent().stream().map(this::toItemMap).toList(), p, page, size));
    }

    @PostMapping("/datasets/{id}/items")
    public ApiResponse<Map<String, Object>> addItem(@PathVariable UUID id,
                                                    @Valid @RequestBody EvaluationItemRequest req) {
        companyScopeService.assertDatasetWritable(id);
        UUID companyId = companyScopeService.isUnrestrictedDataScope() ? null : TokenContext.getCompanyId();
        EvaluationItem item = datasetService.addItem(id, req, companyId);
        return ApiResponse.success(toItemMap(item));
    }

    @PutMapping("/items/{id}")
    public ApiResponse<Map<String, Object>> updateItem(@PathVariable UUID id,
                                                       @Valid @RequestBody EvaluationItemRequest req) {
        EvaluationItem item = itemRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "题目不存在"));
        companyScopeService.assertDatasetWritable(item.getDatasetId());
        EvaluationItem updated = datasetService.updateItem(id, req);
        return ApiResponse.success(toItemMap(updated));
    }

    @DeleteMapping("/items/{id}")
    public ApiResponse<Void> deleteItem(@PathVariable UUID id) {
        EvaluationItem item = itemRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "题目不存在"));
        companyScopeService.assertDatasetWritable(item.getDatasetId());
        datasetService.deleteItem(id);
        return ApiResponse.success(null);
    }

    // ==================== 序列化 ====================

    /** 校验生成候选的来源条目可读：super_admin 全可读，企业管理员可读「本企业 + 公共」。 */
    private void assertSourcesReadable(String sourceType, java.util.List<UUID> sourceIds) {
        for (UUID sid : sourceIds) {
            if (EvaluationGenerationJob.SOURCE_TYPE_KNOWLEDGE.equals(sourceType)) {
                companyScopeService.assertKnowledgeReadable(sid);
            } else {
                companyScopeService.assertCaseReadable(sid);
            }
        }
    }

    private Map<UUID, String> resolveCreatorNames(java.util.List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        userRepository.findAllById(ids).forEach(u -> names.put(u.getId(), u.getName()));
        return names;
    }

    private Map<String, Object> toDatasetMap(EvaluationDataset d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId().toString());
        m.put("companyId", d.getCompanyId() != null ? d.getCompanyId().toString() : null);
        m.put("name", d.getName());
        m.put("description", d.getDescription());
        m.put("purpose", d.getPurpose());
        m.put("status", d.getStatus());
        m.put("currentVersion", d.getCurrentVersion());
        m.put("itemCount", d.getItemCount());
        m.put("lastReviewReason", d.getLastReviewReason());
        m.put("reviewedAt", d.getReviewedAt() != null ? d.getReviewedAt().toString() : null);
        m.put("createdBy", d.getCreatedBy() != null ? d.getCreatedBy().toString() : null);
        m.put("createdAt", d.getCreatedAt() != null ? d.getCreatedAt().toString() : null);
        m.put("updatedAt", d.getUpdatedAt() != null ? d.getUpdatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> toItemMap(EvaluationItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId().toString());
        m.put("datasetId", item.getDatasetId().toString());
        m.put("version", item.getVersion());
        m.put("question", item.getQuestion());
        m.put("generated", Boolean.TRUE.equals(item.getGenerated()));
        m.put("context", item.getContext());
        m.put("expectedPoints", item.getExpectedPoints());
        m.put("forbiddenPoints", item.getForbiddenPoints());
        m.put("citationRequirement", item.getCitationRequirement());
        m.put("sceneTag", item.getSceneTag());
        m.put("goldBasis", item.getGoldBasis());
        m.put("scoringRule", item.getScoringRule());
        m.put("retrievalExpected", item.getRetrievalExpected());
        m.put("orderNo", item.getOrderNo());
        m.put("status", item.getStatus());
        m.put("evidence", item.getEvidence());
        m.put("generationJobId", item.getGenerationJobId() != null ? item.getGenerationJobId().toString() : null);
        m.put("reviewedAt", item.getReviewedAt() != null ? item.getReviewedAt().toString() : null);
        m.put("reviewNote", item.getReviewNote());
        m.put("createdAt", item.getCreatedAt() != null ? item.getCreatedAt().toString() : null);
        m.put("updatedAt", item.getUpdatedAt() != null ? item.getUpdatedAt().toString() : null);
        return m;
    }
}
