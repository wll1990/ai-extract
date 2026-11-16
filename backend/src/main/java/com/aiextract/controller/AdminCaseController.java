package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.PromptLoader;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.IndustryCaseRequest;
import com.aiextract.dto.SuggestRevisionRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.CaseUploadTask;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.User;
import com.aiextract.repository.CaseUploadTaskRepository;
import com.aiextract.repository.CompanyRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.CaseCleaningService;
import com.aiextract.service.ContentImportService;
import com.aiextract.service.KnowledgeCaseService;
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
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 案例库管理 Controller — 与知识库同套 CRUD + 上传 + AI 整理（版本化）。
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/admin/cases")
@RequiredArgsConstructor
public class AdminCaseController {

    private final IndustryCaseRepository caseRepository;
    private final CaseUploadTaskRepository uploadTaskRepository;
    private final UserRepository userRepository;
    private final KnowledgeCaseService knowledgeCaseService;
    private final CaseCleaningService caseCleaningService;
    private final CompanyScopeService companyScopeService;
    private final CompanyRepository companyRepository;
    private final ContentImportService contentImportService;
    private final com.aiextract.service.ContentDeletionService contentDeletionService;
    private final PromptLoader promptLoader;

    // ==================== 查询 ====================

    @GetMapping
    public ApiResponse<Map<String, Object>> listCases(
            @RequestParam(required = false) String funnelStage,
            @RequestParam(required = false) String sceneTag,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "false") boolean aiGenerated,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        boolean scopeAll = companyScopeService.isUnrestrictedDataScope();
        UUID companyId = TokenContext.getCompanyId();
        // 过期态是派生态：expired=只看已过期，active=只看未过期
        boolean expiredOnly = "expired".equals(status);
        boolean excludeExpired = "active".equals(status);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        Page<IndustryCase> p = caseRepository.search(
                scopeAll, companyId, scope, !scopeAll, funnelStage, sceneTag, result,
                expiredOnly ? "active" : status, keyword, expiredOnly, excludeExpired,
                aiGenerated, PageRequest.of(safePage - 1, safeSize));
        Map<UUID, String> contributorNames = resolveCreatorNames(p.getContent().stream()
                .map(IndustryCase::getContributorId).filter(Objects::nonNull).toList());
        // 超管跨企业查看时补企业名，避免只看到一串 companyId
        Map<UUID, String> companyNames = scopeAll
                ? resolveCompanyNames(p.getContent().stream().map(IndustryCase::getCompanyId).filter(Objects::nonNull).toList())
                : Map.of();
        List<Map<String, Object>> content = p.getContent().stream().map(c -> {
            Map<String, Object> m = toMap(c);
            m.put("contributorName", c.getContributorId() != null ? contributorNames.get(c.getContributorId()) : null);
            m.put("companyName", c.getCompanyId() != null ? companyNames.get(c.getCompanyId()) : null);
            return m;
        }).toList();
        return ApiResponse.success(PageResponse.of(content, p, safePage, safeSize));
    }

    /** 场景标签候选值（当前可见范围内的去重值），供列表筛选下拉使用 */
    @GetMapping("/scene-tags")
    public ApiResponse<List<String>> sceneTags() {
        boolean scopeAll = companyScopeService.isUnrestrictedDataScope();
        return ApiResponse.success(caseRepository.findDistinctSceneTags(scopeAll, TokenContext.getCompanyId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getCase(@PathVariable UUID id) {
        companyScopeService.assertCaseReadable(id);
        IndustryCase c = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "案例不存在"));
        return ApiResponse.success(toMap(c));
    }

    /**
     * 溯源：返回来源上传文档的文件名与短时效下载地址。
     * 手工录入、外部导入、公共库复制没有本企业上传任务，返回 404。
     */
    @GetMapping("/{id}/source-file")
    public ApiResponse<Map<String, Object>> getSourceFile(@PathVariable UUID id) {
        companyScopeService.assertCaseReadable(id);
        IndustryCase c = caseRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "案例不存在"));
        if (c.getSourceTaskId() == null) {
            throw new BusinessException(404, "这条案例没有关联的上传文档");
        }
        return ApiResponse.success(caseCleaningService.sourceFile(c.getSourceTaskId()));
    }

    /** 该案例派生出的新版本（AI 整理草稿等），详情页用来提示「有修订待处理」。 */
    @GetMapping("/{id}/revisions")
    public ApiResponse<List<Map<String, Object>>> listRevisions(@PathVariable UUID id) {
        companyScopeService.assertCaseReadable(id);
        List<Map<String, Object>> result = caseRepository
                .findBySupersedesIdOrderByVersionDesc(id).stream()
                .map(this::toMap)
                .toList();
        return ApiResponse.success(result);
    }

    /** 完整版本链（v1 → 当前），详情页版本时间线使用。 */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<Map<String, Object>>> listVersions(@PathVariable UUID id) {
        companyScopeService.assertCaseReadable(id);
        return ApiResponse.success(knowledgeCaseService.caseVersions(id).stream().map(this::toMap).toList());
    }

    /** 以历史案例版本内容生成新草稿（回滚），不修改历史版本。 */
    @PostMapping("/{id}/versions/{versionId}/rollback")
    public ApiResponse<Map<String, Object>> rollback(@PathVariable UUID id, @PathVariable UUID versionId) {
        companyScopeService.assertCaseWritable(id);
        companyScopeService.assertCaseReadable(versionId);
        return ApiResponse.success(toMap(knowledgeCaseService.rollbackCase(id, versionId)));
    }

    /** 被引用情况：最近 N 天哪些分身命中过这条案例。 */
    @GetMapping("/{id}/references")
    public ApiResponse<List<Map<String, Object>>> references(@PathVariable UUID id,
                                                             @RequestParam(defaultValue = "30") int days) {
        companyScopeService.assertCaseReadable(id);
        return ApiResponse.success(knowledgeCaseService.caseReferences(id, days));
    }

    // ==================== 写操作 ====================

    @PostMapping
    public ApiResponse<Map<String, Object>> createCase(@Valid @RequestBody IndustryCaseRequest req) {
        UUID companyId = companyScopeService.isUnrestrictedDataScope() ? null : TokenContext.getCompanyId();
        IndustryCase c = knowledgeCaseService.createCase(req, companyId);
        return ApiResponse.success(toMap(c));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> updateCase(@PathVariable UUID id,
                                                       @Valid @RequestBody IndustryCaseRequest req) {
        companyScopeService.assertCaseWritable(id);
        IndustryCase c = knowledgeCaseService.updateCase(id, req);
        return ApiResponse.success(toMap(c));
    }

    @PostMapping("/{id}/deprecate")
    public ApiResponse<Void> deprecateCase(@PathVariable UUID id) {
        companyScopeService.assertCaseWritable(id);
        knowledgeCaseService.deprecateCase(id);
        return ApiResponse.success(null);
    }

    /** 彻底删除：仅草稿/待审/已下架，且无派生版本、无培训引用；不可恢复 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteCase(@PathVariable UUID id) {
        companyScopeService.assertCaseWritable(id);
        contentDeletionService.deleteCase(id, TokenContext.get());
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<Map<String, Object>> approveCase(@PathVariable UUID id,
                                                        @RequestParam(required = false) String expireAt) {
        companyScopeService.assertCaseWritable(id);
        LocalDateTime expire = parseExpireAt(expireAt);
        IndustryCase c = knowledgeCaseService.approveCase(id, expire);
        return ApiResponse.success(toMap(c));
    }

    /** AI 整理案例：基于当前版本生成新草稿，原版本保持不变。 */
    @PostMapping("/{id}/suggest-revision")
    public ApiResponse<Map<String, Object>> suggestRevision(@PathVariable UUID id,
                                                            @Valid @RequestBody SuggestRevisionRequest req) {
        companyScopeService.assertCaseWritable(id);
        IndustryCase draft = knowledgeCaseService.suggestCaseRevision(id, req.getInstruction());
        return ApiResponse.success(toMap(draft));
    }

    /** AI 针对当前案例给出修改建议（只读，不落库），用于 AI 整理弹窗 */
    @PostMapping("/{id}/improvement-suggestions")
    public ApiResponse<List<Map<String, String>>> improvementSuggestions(@PathVariable UUID id) {
        companyScopeService.assertCaseWritable(id);
        return ApiResponse.success(knowledgeCaseService.suggestCaseImprovements(id));
    }

    /** 提交到公共库 — 生成公共待审候选，来源企业只做溯源 */
    @PostMapping("/{id}/submit-public")
    public ApiResponse<Map<String, Object>> submitPublic(@PathVariable UUID id) {
        companyScopeService.assertCaseWritable(id);
        return ApiResponse.success(toMap(knowledgeCaseService.submitCaseToPublic(id)));
    }

    /** 恢复已废弃案例（30 天内），恢复后重新索引 */
    @PostMapping("/{id}/restore")
    public ApiResponse<Map<String, Object>> restoreCase(@PathVariable UUID id) {
        companyScopeService.assertCaseWritable(id);
        return ApiResponse.success(toMap(knowledgeCaseService.restoreCase(id)));
    }

    /** 重建向量索引 — 异步执行，前端轮询详情查看 embeddingStatus */
    @PostMapping("/{id}/reindex")
    public ApiResponse<Void> reindexCase(@PathVariable UUID id) {
        companyScopeService.assertCaseWritable(id);
        knowledgeCaseService.requestCaseReindex(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/copy")
    public ApiResponse<Map<String, Object>> copyCase(@PathVariable UUID id) {
        UUID companyId = TokenContext.getCompanyId();
        IndustryCase copy = knowledgeCaseService.copyPublicCaseToCompany(id, companyId);
        return ApiResponse.success(toMap(copy));
    }

    // ==================== 外部预提取导入 ====================

    /** 外部预提取提示词（用户在自选模型里先出 JSON，再贴回导入） */
    @GetMapping("/import-prompt")
    public ApiResponse<Map<String, Object>> importPrompt() {
        return ApiResponse.success(Map.of("prompt", promptLoader.load("external_case_import.md")));
    }

    /**
     * 批量导入外部预提取的案例 JSON。dryRun=true 只校验不落库。
     * 书籍/不达标条目落草稿，人工补齐后再审批。
     */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importCases(@RequestBody Map<String, Object> body) {
        UUID companyId = companyScopeService.isUnrestrictedDataScope() ? null : TokenContext.getCompanyId();
        boolean dryRun = Boolean.TRUE.equals(body.get("dryRun"));
        String sourceKind = body.get("sourceKind") instanceof String s ? s : "internal";
        String sourceName = body.get("sourceName") instanceof String s ? s : null;
        List<Map<String, Object>> items = parseImportItems(body);
        Map<String, Object> result = contentImportService.importCases(
                items, dryRun, sourceKind, sourceName, companyId, TokenContext.get());
        return ApiResponse.success(result);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseImportItems(Map<String, Object> body) {
        Object raw = body.get("items");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    // ==================== 上传 ====================

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                   @RequestParam(required = false) String sourceKind) {
        UUID companyId = TokenContext.getCompanyId();
        UUID operatorId = TokenContext.get();
        UUID taskId = caseCleaningService.uploadCase(file, companyId, operatorId, sourceKind);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", taskId.toString());
        return ApiResponse.success(m);
    }

    /** 上传任务列表：离开新建页后回来还能找到进度与产出（此前任务 ID 只存在页面内存里） */
    @GetMapping("/upload/tasks")
    public ApiResponse<Map<String, Object>> uploadTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        UUID companyId = TokenContext.getCompanyId();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 50));
        Page<CaseUploadTask> p = uploadTaskRepository.findByCompanyIdOrderByCreatedAtDesc(
                companyId, PageRequest.of(safePage - 1, safeSize));
        List<Map<String, Object>> content = p.getContent().stream().map(this::toTaskMap).toList();
        return ApiResponse.success(PageResponse.of(content, p, safePage, safeSize));
    }

    @GetMapping("/upload/{taskId}")
    public ApiResponse<Map<String, Object>> uploadStatus(@PathVariable UUID taskId) {
        CaseUploadTask task = uploadTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "上传任务不存在"));
        assertUploadTaskAccessible(task.getCompanyId());
        return ApiResponse.success(toTaskMap(task));
    }

    /** 续传上传任务：从 processedSections 检查点继续，不重做已完成章节。 */
    @PostMapping("/upload/{taskId}/retry")
    public ApiResponse<Map<String, Object>> retryUpload(@PathVariable UUID taskId) {
        CaseUploadTask task = uploadTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "上传任务不存在"));
        assertUploadTaskAccessible(task.getCompanyId());
        caseCleaningService.retryCase(taskId);
        CaseUploadTask updated = uploadTaskRepository.findById(taskId).orElse(task);
        return ApiResponse.success(toTaskMap(updated));
    }

    /** 断言当前用户可访问该上传任务（super_admin 或本企业） */
    private void assertUploadTaskAccessible(UUID taskCompanyId) {
        if (!companyScopeService.isUnrestrictedDataScope()) {
            UUID companyId = TokenContext.getCompanyId();
            if (companyId == null || !companyId.equals(taskCompanyId)) {
                throw new BusinessException(403, "无权访问其他企业的上传任务");
            }
        }
    }

    // ==================== 序列化 ====================

    private LocalDateTime parseExpireAt(String expireAt) {
        if (expireAt == null || expireAt.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(expireAt);
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "失效时间格式错误，需 ISO 格式如 2026-12-01T10:00:00");
        }
    }

    /** 批量查贡献人姓名（一次 findAllById，避免 N+1） */
    private Map<UUID, String> resolveCreatorNames(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        userRepository.findAllById(ids).forEach(u -> names.put(u.getId(), u.getName()));
        return names;
    }

    /** 批量查企业名（超管跨企业列表用，一次 findAllById，避免 N+1） */
    private Map<UUID, String> resolveCompanyNames(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        companyRepository.findAllById(ids).forEach(c -> names.put(c.getId(), c.getName()));
        return names;
    }

    private Map<String, Object> toMap(IndustryCase c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId().toString());
        m.put("companyId", c.getCompanyId() != null ? c.getCompanyId().toString() : null);
        m.put("sourceCompanyId", c.getSourceCompanyId() != null ? c.getSourceCompanyId().toString() : null);
        m.put("title", c.getTitle());
        m.put("customerProfile", c.getCustomerProfile());
        m.put("funnelStage", c.getFunnelStage());
        m.put("sceneTag", c.getSceneTag());
        m.put("dialogueQuote", c.getDialogueQuote());
        m.put("objection", c.getObjection());
        m.put("emotionChange", c.getEmotionChange());
        m.put("keyActions", c.getKeyActions());
        m.put("scripts", c.getScripts());
        m.put("advanceNodes", c.getAdvanceNodes());
        m.put("result", c.getResult());
        m.put("reusablePoints", c.getReusablePoints());
        m.put("failurePoints", c.getFailurePoints());
        m.put("source", c.getSource());
        m.put("sourceTaskId", c.getSourceTaskId() != null ? c.getSourceTaskId().toString() : null);
        m.put("sourceDocument", c.getSourceDocument());
        m.put("evidence", c.getEvidence());
        m.put("generationJobId", c.getGenerationJobId() != null ? c.getGenerationJobId().toString() : null);
        m.put("generated", c.getGenerationJobId() != null);
        m.put("reviewedAt", c.getReviewedAt() != null ? c.getReviewedAt().toString() : null);
        m.put("reviewNote", c.getReviewNote());
        m.put("contributorId", c.getContributorId() != null ? c.getContributorId().toString() : null);
        m.put("confidence", c.getConfidence());
        m.put("version", c.getVersion());
        m.put("supersedesId", c.getSupersedesId() != null ? c.getSupersedesId().toString() : null);
        m.put("effectiveAt", c.getEffectiveAt() != null ? c.getEffectiveAt().toString() : null);
        m.put("expireAt", c.getExpireAt() != null ? c.getExpireAt().toString() : null);
        m.put("status", c.getStatus());
        m.put("origin", c.getOrigin());
        m.put("qualityIssues", c.getQualityIssues());
        m.put("qualityWarnings", c.getQualityWarnings());
        m.put("embeddingStatus", c.getEmbeddingStatus());
        m.put("indexedAt", c.getIndexedAt() != null ? c.getIndexedAt().toString() : null);
        m.put("indexError", c.getIndexError());
        m.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        m.put("updatedAt", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> toTaskMap(CaseUploadTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId().toString());
        m.put("companyId", t.getCompanyId() != null ? t.getCompanyId().toString() : null);
        m.put("fileName", t.getFileName());
        m.put("status", t.getStatus());
        m.put("producedCount", t.getProducedCount());
        m.put("totalSections", t.getTotalSections());
        m.put("processedSections", t.getProcessedSections());
        m.put("sourceKind", t.getSourceKind());
        m.put("skippedCount", t.getSkippedCount());
        m.put("failedSections", t.getFailedSections());
        m.put("guideMessage", t.getGuideMessage());
        m.put("errorMessage", t.getErrorMessage());
        m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        return m;
    }
}
