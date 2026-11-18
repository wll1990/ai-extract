package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.PromptLoader;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.KnowledgeEntryRequest;
import com.aiextract.dto.SuggestRevisionRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.model.KnowledgeUploadTask;
import com.aiextract.model.User;
import com.aiextract.repository.CompanyRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.KnowledgeUploadTaskRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.ContentImportService;
import com.aiextract.service.KnowledgeCaseRetriever;
import com.aiextract.service.KnowledgeCaseService;
import com.aiextract.service.KnowledgeCleaningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 知识库管理 Controller — 只做参数校验、权限断言和路由，业务在 Service 层。
 *
 * <p>列表按角色分支：super_admin 看全部，company_admin 看「本企业 + 公共」。
 * 过期态由前端按 expire_at 派生，列表原样返回 status + expireAt。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/admin/knowledge")
@RequiredArgsConstructor
public class AdminKnowledgeController {

    private final KnowledgeEntryRepository knowledgeRepository;
    private final KnowledgeUploadTaskRepository uploadTaskRepository;
    private final UserRepository userRepository;
    private final KnowledgeCaseService knowledgeCaseService;
    private final KnowledgeCleaningService knowledgeCleaningService;
    private final CompanyScopeService companyScopeService;
    private final KnowledgeCaseRetriever knowledgeCaseRetriever;
    private final CompanyRepository companyRepository;
    private final ContentImportService contentImportService;
    private final PromptLoader promptLoader;
    private final com.aiextract.service.ContentDeletionService contentDeletionService;

    /** 线上聊天实际使用的阈值：检索测试据此标记「会不会进入分身回答」。 */
    @Value("${app.rag.kc-knowledge-min-similarity:0.45}")
    private double knowledgeMinSimilarity;

    @Value("${app.rag.kc-case-min-similarity:0.50}")
    private double caseMinSimilarity;

    // ==================== 查询 ====================

    @GetMapping
    public ApiResponse<Map<String, Object>> listKnowledge(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String sourceType,
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
        Page<KnowledgeEntry> p = knowledgeRepository.search(
                scopeAll, companyId, scope, !scopeAll, category, sourceType,
                expiredOnly ? "active" : status, keyword, expiredOnly, excludeExpired,
                aiGenerated, PageRequest.of(safePage - 1, safeSize));
        Map<UUID, String> creatorNames = resolveCreatorNames(p.getContent().stream()
                .map(KnowledgeEntry::getCreatedBy).filter(Objects::nonNull).toList());
        // 超管跨企业查看时补企业名，避免只看到一串 companyId
        Map<UUID, String> companyNames = scopeAll
                ? resolveCompanyNames(p.getContent().stream().map(KnowledgeEntry::getCompanyId).filter(Objects::nonNull).toList())
                : Map.of();
        List<Map<String, Object>> content = p.getContent().stream().map(k -> {
            Map<String, Object> m = toMap(k);
            m.put("createdByName", k.getCreatedBy() != null ? creatorNames.get(k.getCreatedBy()) : null);
            m.put("companyName", k.getCompanyId() != null ? companyNames.get(k.getCompanyId()) : null);
            return m;
        }).toList();
        return ApiResponse.success(PageResponse.of(content, p, safePage, safeSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getKnowledge(@PathVariable UUID id) {
        companyScopeService.assertKnowledgeReadable(id);
        KnowledgeEntry k = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在"));
        return ApiResponse.success(toMap(k));
    }

    /**
     * 溯源：返回来源上传文档的文件名与短时效下载地址。
     * 手工录入、外部导入、公共库复制没有本企业上传任务，返回 404。
     */
    @GetMapping("/{id}/source-file")
    public ApiResponse<Map<String, Object>> getSourceFile(@PathVariable UUID id) {
        companyScopeService.assertKnowledgeReadable(id);
        KnowledgeEntry k = knowledgeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "知识不存在"));
        if (k.getSourceTaskId() == null) {
            throw new BusinessException(404, "这条知识没有关联的上传文档");
        }
        return ApiResponse.success(knowledgeCleaningService.sourceFile(k.getSourceTaskId()));
    }

    /** 该知识派生出的新版本（AI 整理草稿等），详情页用来提示「有修订待处理」。 */
    @GetMapping("/{id}/revisions")
    public ApiResponse<List<Map<String, Object>>> listRevisions(@PathVariable UUID id) {
        companyScopeService.assertKnowledgeReadable(id);
        List<Map<String, Object>> result = knowledgeRepository
                .findBySupersedesIdOrderByVersionDesc(id).stream()
                .map(this::toMap)
                .toList();
        return ApiResponse.success(result);
    }

    /** 完整版本链（v1 → 当前），详情页版本时间线使用。 */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<Map<String, Object>>> listVersions(@PathVariable UUID id) {
        companyScopeService.assertKnowledgeReadable(id);
        return ApiResponse.success(knowledgeCaseService.knowledgeVersions(id).stream().map(this::toMap).toList());
    }

    /** 以历史版本内容生成新草稿（回滚），不修改历史版本。 */
    @PostMapping("/{id}/versions/{versionId}/rollback")
    public ApiResponse<Map<String, Object>> rollback(@PathVariable UUID id, @PathVariable UUID versionId) {
        companyScopeService.assertKnowledgeWritable(id);
        companyScopeService.assertKnowledgeReadable(versionId);
        return ApiResponse.success(toMap(knowledgeCaseService.rollbackKnowledge(id, versionId)));
    }

    /** 被引用情况：最近 N 天哪些分身命中过这条知识。 */
    @GetMapping("/{id}/references")
    public ApiResponse<List<Map<String, Object>>> references(@PathVariable UUID id,
                                                             @RequestParam(defaultValue = "30") int days) {
        companyScopeService.assertKnowledgeReadable(id);
        return ApiResponse.success(knowledgeCaseService.knowledgeReferences(id, days));
    }

    /**
     * 检索测试（KN-07）：输入问题，返回命中的知识与案例及相似度。
     * 只读调试入口，不写检索日志、不影响线上统计。
     */
    @PostMapping("/retrieval-test")
    public ApiResponse<Map<String, Object>> retrievalTest(@RequestBody Map<String, Object> body) {
        String query = body.get("query") instanceof String q ? q.trim() : "";
        if (query.isEmpty()) {
            throw new BusinessException(400, "请输入要检索的问题");
        }
        int topK = 5;
        if (body.get("topK") instanceof Number n) {
            topK = Math.max(1, Math.min(n.intValue(), 20));
        }
        // 知识与案例各测各的：知识库页只测知识，案例库页只测案例；all 仅保留给旧调用
        String scope = body.get("scope") instanceof String s ? s.trim() : "all";
        if (!"all".equals(scope) && !"knowledge".equals(scope) && !"case".equals(scope)) {
            throw new BusinessException(400, "scope 只能是 knowledge / case / all");
        }
        boolean wantKnowledge = !"case".equals(scope);
        boolean wantCase = !"knowledge".equals(scope);
        UUID companyId = TokenContext.getCompanyId();
        KnowledgeCaseRetriever.KnowledgeCaseHits hits;
        try {
            // 诊断模式：用更低的「近失下限」召回，才能解释「为什么这条没被引用」。
            // 每条结果带 effective 标记，前端据此区分「会进入分身」和「未达阈值」。
            hits = knowledgeCaseRetriever.retrieve(query, companyId, topK,
                    KnowledgeCaseRetriever.DIAGNOSTIC_KNOWLEDGE_MIN_SIMILARITY,
                    KnowledgeCaseRetriever.DIAGNOSTIC_CASE_MIN_SIMILARITY);
        } catch (RuntimeException e) {
            // 检索依赖外部向量模型：额度不足/服务不可用时给 503，而不是 500
            throw new BusinessException(503, "检索服务暂不可用（向量模型调用失败），请稍后重试");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("topK", topK);
        result.put("scope", scope);
        result.put("knowledgeThreshold", knowledgeMinSimilarity);
        result.put("caseThreshold", caseMinSimilarity);
        result.put("knowledge", wantKnowledge ? hits.knowledge().stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.id());
            m.put("title", h.title());
            m.put("content", h.content());
            m.put("category", h.category());
            m.put("source", h.source());
            m.put("similarity", h.similarity());
            m.put("effective", h.similarity() >= knowledgeMinSimilarity);
            return m;
        }).toList() : java.util.List.of());
        result.put("cases", wantCase ? hits.cases().stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.id());
            m.put("title", h.title());
            m.put("sceneTag", h.sceneTag());
            m.put("objection", h.objection());
            m.put("similarity", h.similarity());
            m.put("effective", h.similarity() >= caseMinSimilarity);
            return m;
        }).toList() : java.util.List.of());
        result.put("knowledgeCount", wantKnowledge ? hits.knowledge().size() : 0);
        result.put("caseCount", wantCase ? hits.cases().size() : 0);
        return ApiResponse.success(result);
    }

    // ==================== 写操作 ====================

    @PostMapping
    public ApiResponse<Map<String, Object>> createKnowledge(@Valid @RequestBody KnowledgeEntryRequest req) {
        UUID companyId = companyScopeService.isUnrestrictedDataScope() ? null : TokenContext.getCompanyId();
        KnowledgeEntry k = knowledgeCaseService.createKnowledge(req, companyId);
        return ApiResponse.success(toMap(k));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> updateKnowledge(@PathVariable UUID id,
                                                            @Valid @RequestBody KnowledgeEntryRequest req) {
        companyScopeService.assertKnowledgeWritable(id);
        KnowledgeEntry k = knowledgeCaseService.updateKnowledge(id, req);
        return ApiResponse.success(toMap(k));
    }

    @PostMapping("/{id}/deprecate")
    public ApiResponse<Void> deprecateKnowledge(@PathVariable UUID id) {
        companyScopeService.assertKnowledgeWritable(id);
        knowledgeCaseService.deprecateKnowledge(id);
        return ApiResponse.success(null);
    }

    /** 彻底删除：仅草稿/待审/已下架，且无派生版本、无培训引用；不可恢复 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteKnowledge(@PathVariable UUID id) {
        companyScopeService.assertKnowledgeWritable(id);
        contentDeletionService.deleteKnowledge(id, TokenContext.get());
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<Map<String, Object>> approveKnowledge(@PathVariable UUID id,
                                                             @RequestParam(required = false) String expireAt) {
        companyScopeService.assertKnowledgeWritable(id);
        LocalDateTime expire = parseExpireAt(expireAt);
        KnowledgeEntry k = knowledgeCaseService.approveKnowledge(id, expire);
        return ApiResponse.success(toMap(k));
    }

    /**
     * KN-08 批量操作：approve / deprecate / reindex / category。
     * 逐条执行，失败项返回原因，不因单条失败中断整批。
     */
    @PostMapping("/batch")
    public ApiResponse<Map<String, Object>> batch(@RequestBody Map<String, Object> body) {
        String action = body.get("action") instanceof String a ? a.trim() : "";
        if (action.isEmpty()) {
            throw new BusinessException(400, "缺少 action");
        }
        List<?> rawIds = body.get("ids") instanceof List<?> l ? l : List.of();
        if (rawIds.isEmpty()) {
            throw new BusinessException(400, "请选择知识条目");
        }
        if (rawIds.size() > 200) {
            throw new BusinessException(400, "单次最多处理 200 条");
        }
        List<UUID> ids = new ArrayList<>();
        for (Object o : rawIds) {
            try {
                ids.add(UUID.fromString(String.valueOf(o)));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "非法知识 ID: " + o);
            }
        }
        String category = body.get("category") instanceof String c ? c.trim() : null;
        if ("category".equals(action) && (category == null || category.isEmpty())) {
            throw new BusinessException(400, "请选择目标分类");
        }

        // 批量审批：上传采纳/回滚产生的条目没有失效时间，审批又强制要求；
        // 批量场景统一用入参，缺省按 1 年处理（与复盘沉淀口径一致），否则整批必然 400。
        LocalDateTime parsedExpire = null;
        if ("approve".equals(action)) {
            parsedExpire = parseExpireAt(
                    body.get("expireAt") instanceof String s ? s : null);
        }

        int success = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (UUID id : ids) {
            try {
                companyScopeService.assertKnowledgeWritable(id);
                switch (action) {
                    case "approve" -> {
                        // 显式日期优先；否则保留条目已有失效时间；两者都空才补 1 年
                        LocalDateTime itemExpire = parsedExpire;
                        if (itemExpire == null) {
                            LocalDateTime existing = knowledgeRepository.findById(id)
                                    .map(KnowledgeEntry::getExpireAt).orElse(null);
                            itemExpire = existing != null ? null : LocalDateTime.now().plusYears(1);
                        }
                        knowledgeCaseService.approveKnowledge(id, itemExpire);
                    }
                    case "deprecate" -> knowledgeCaseService.deprecateKnowledge(id);
                    case "reindex" -> knowledgeCaseService.requestKnowledgeReindex(id);
                    case "category" -> knowledgeCaseService.updateKnowledgeCategory(id, category);
                    default -> throw new BusinessException(400, "不支持的批量操作: " + action);
                }
                success++;
            } catch (BusinessException e) {
                failures.add(Map.of("id", id.toString(),
                        "message", e.getMessage() == null ? "操作失败" : e.getMessage()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("failed", failures.size());
        result.put("failures", failures);
        return ApiResponse.success(result);
    }

    /** 提交到公共库 — 生成公共待审候选，来源企业只做溯源 */
    @PostMapping("/{id}/submit-public")
    public ApiResponse<Map<String, Object>> submitPublic(@PathVariable UUID id) {
        companyScopeService.assertKnowledgeWritable(id);
        return ApiResponse.success(toMap(knowledgeCaseService.submitKnowledgeToPublic(id)));
    }

    /** 恢复已废弃知识（30 天内），恢复后重新索引 */
    @PostMapping("/{id}/restore")
    public ApiResponse<Map<String, Object>> restoreKnowledge(@PathVariable UUID id) {
        companyScopeService.assertKnowledgeWritable(id);
        return ApiResponse.success(toMap(knowledgeCaseService.restoreKnowledge(id)));
    }

    /** 重建向量索引 — 异步执行，前端轮询详情查看 embeddingStatus */
    @PostMapping("/{id}/reindex")
    public ApiResponse<Void> reindexKnowledge(@PathVariable UUID id) {
        companyScopeService.assertKnowledgeWritable(id);
        knowledgeCaseService.requestKnowledgeReindex(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/copy")
    public ApiResponse<Map<String, Object>> copyKnowledge(@PathVariable UUID id) {
        UUID companyId = TokenContext.getCompanyId();
        KnowledgeEntry copy = knowledgeCaseService.copyPublicKnowledgeToCompany(id, companyId);
        return ApiResponse.success(toMap(copy));
    }

    @PostMapping("/{id}/suggest-revision")
    public ApiResponse<Map<String, Object>> suggestRevision(@PathVariable UUID id,
                                                            @Valid @RequestBody SuggestRevisionRequest req) {
        companyScopeService.assertKnowledgeWritable(id);
        KnowledgeEntry draft = knowledgeCaseService.suggestRevision(id, req.getInstruction());
        return ApiResponse.success(toMap(draft));
    }

    /** AI 针对当前知识给出修改建议（只读，不落库），用于 AI 整理弹窗 */
    @PostMapping("/{id}/improvement-suggestions")
    public ApiResponse<List<Map<String, String>>> improvementSuggestions(@PathVariable UUID id) {
        companyScopeService.assertKnowledgeWritable(id);
        return ApiResponse.success(knowledgeCaseService.suggestKnowledgeImprovements(id));
    }

    // ==================== 外部预提取导入 ====================

    /** 外部预提取提示词（用户先在自选模型里出 JSON，再贴回导入） */
    @GetMapping("/import-prompt")
    public ApiResponse<Map<String, Object>> importPrompt() {
        return ApiResponse.success(Map.of("prompt", promptLoader.load("external_knowledge_import.md")));
    }

    /** 批量导入外部预提取的知识 JSON；dryRun=true 只校验不落库。 */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importKnowledge(@RequestBody Map<String, Object> body) {
        UUID companyId = companyScopeService.isUnrestrictedDataScope() ? null : TokenContext.getCompanyId();
        boolean dryRun = Boolean.TRUE.equals(body.get("dryRun"));
        String sourceKind = body.get("sourceKind") instanceof String s ? s : "internal";
        String sourceName = body.get("sourceName") instanceof String s ? s : null;
        List<Map<String, Object>> items = parseImportItems(body);
        Map<String, Object> result = contentImportService.importKnowledge(
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
        UUID taskId = knowledgeCleaningService.uploadKnowledge(file, companyId, operatorId, sourceKind);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", taskId.toString());
        return ApiResponse.success(m);
    }

    /** 上传任务列表：离开新建页后回来还能找到进度与草稿（此前任务 ID 只存在页面内存里） */
    @GetMapping("/upload/tasks")
    public ApiResponse<Map<String, Object>> uploadTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        UUID companyId = TokenContext.getCompanyId();
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 50));
        Page<KnowledgeUploadTask> p = uploadTaskRepository.findByCompanyIdOrderByCreatedAtDesc(
                companyId, PageRequest.of(safePage - 1, safeSize));
        List<Map<String, Object>> content = p.getContent().stream().map(this::toTaskMap).toList();
        return ApiResponse.success(PageResponse.of(content, p, safePage, safeSize));
    }

    @GetMapping("/upload/{taskId}")
    public ApiResponse<Map<String, Object>> uploadStatus(@PathVariable UUID taskId) {
        KnowledgeUploadTask task = uploadTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "上传任务不存在"));
        assertUploadTaskAccessible(task.getCompanyId());
        return ApiResponse.success(toTaskMap(task));
    }

    /** 续传上传任务：从 processedSections 检查点继续，不重做已完成章节。 */
    @PostMapping("/upload/{taskId}/retry")
    public ApiResponse<Map<String, Object>> retryUpload(@PathVariable UUID taskId) {
        KnowledgeUploadTask task = uploadTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "上传任务不存在"));
        assertUploadTaskAccessible(task.getCompanyId());
        knowledgeCleaningService.retryUpload(taskId);
        KnowledgeUploadTask updated = uploadTaskRepository.findById(taskId).orElse(task);
        return ApiResponse.success(toTaskMap(updated));
    }

    /** KN-06 预览：按章节列出上传任务的萃取草稿 */
    @GetMapping("/upload/{taskId}/drafts")
    public ApiResponse<List<Map<String, Object>>> uploadDrafts(@PathVariable UUID taskId) {
        KnowledgeUploadTask task = uploadTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "上传任务不存在"));
        assertUploadTaskAccessible(task.getCompanyId());
        List<Map<String, Object>> items = knowledgeCleaningService.listDrafts(taskId).stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId().toString());
            m.put("sectionNo", d.getSectionNo());
            m.put("title", d.getTitle());
            m.put("content", d.getContent());
            m.put("category", d.getCategory());
            m.put("source", d.getSource());
            m.put("status", d.getStatus());
            m.put("qualityScore", d.getQualityScore());
            m.put("qualityIssues", d.getQualityIssues());
            m.put("qualityWarnings", d.getQualityWarnings());
            return m;
        }).toList();
        return ApiResponse.success(items);
    }

    /** KN-06 批量采纳草稿为待审知识（ids 为空 = 全部 pending） */
    @PostMapping("/upload/{taskId}/adopt")
    public ApiResponse<Map<String, Object>> adoptDrafts(@PathVariable UUID taskId,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        KnowledgeUploadTask task = uploadTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "上传任务不存在"));
        assertUploadTaskAccessible(task.getCompanyId());
        List<UUID> ids = parseDraftIds(body);
        KnowledgeCleaningService.AdoptResult adopted =
                knowledgeCleaningService.adoptDrafts(taskId, ids, TokenContext.get());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("adopted", adopted.adopted());
        result.put("skipped", adopted.skipped().size());
        result.put("skippedItems", adopted.skipped());
        return ApiResponse.success(result);
    }

    /** 人工补齐未达标的草稿：保存后重算质量分，达标即可采纳 */
    @PutMapping("/upload/{taskId}/drafts/{draftId}")
    public ApiResponse<Map<String, Object>> updateDraft(@PathVariable UUID taskId,
                                                        @PathVariable UUID draftId,
                                                        @RequestBody Map<String, Object> body) {
        KnowledgeUploadTask task = uploadTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "上传任务不存在"));
        assertUploadTaskAccessible(task.getCompanyId());
        String title = body.get("title") instanceof String t ? t : null;
        String content = body.get("content") instanceof String c ? c : null;
        String category = body.get("category") instanceof String cat ? cat : null;
        String source = body.get("source") instanceof String s ? s : null;
        if (category == null || category.isBlank()) {
            throw new BusinessException(400, "分类不能为空");
        }
        var d = knowledgeCleaningService.updateDraft(taskId, draftId, title, content, category, source);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId().toString());
        m.put("title", d.getTitle());
        m.put("content", d.getContent());
        m.put("category", d.getCategory());
        m.put("source", d.getSource());
        m.put("qualityScore", d.getQualityScore());
        m.put("qualityIssues", d.getQualityIssues());
        m.put("qualityWarnings", d.getQualityWarnings());
        return ApiResponse.success(m);
    }

    /** KN-06 批量拒绝草稿（ids 为空 = 全部 pending） */
    @PostMapping("/upload/{taskId}/reject")
    public ApiResponse<Map<String, Object>> rejectDrafts(@PathVariable UUID taskId,
                                                         @RequestBody(required = false) Map<String, Object> body) {
        KnowledgeUploadTask task = uploadTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "上传任务不存在"));
        assertUploadTaskAccessible(task.getCompanyId());
        int rejected = knowledgeCleaningService.rejectDrafts(taskId, parseDraftIds(body));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rejected", rejected);
        return ApiResponse.success(result);
    }

    private List<UUID> parseDraftIds(Map<String, Object> body) {
        if (body == null || !(body.get("ids") instanceof List<?> raw) || raw.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (Object o : raw) {
            try {
                ids.add(UUID.fromString(String.valueOf(o)));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "非法草稿 ID: " + o);
            }
        }
        return ids;
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
            LocalDateTime parsed = LocalDateTime.parse(expireAt);
            if (!parsed.isAfter(LocalDateTime.now())) {
                throw new BusinessException(400, "失效时间必须晚于当前时间");
            }
            return parsed;
        } catch (DateTimeParseException e) {
            throw new BusinessException(400, "失效时间格式错误，需 ISO 格式如 2026-12-01T10:00:00");
        }
    }

    /** 批量查创建人姓名（一次 findAllById，避免 N+1） */
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

    private Map<String, Object> toMap(KnowledgeEntry k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId().toString());
        m.put("companyId", k.getCompanyId() != null ? k.getCompanyId().toString() : null);
        m.put("sourceCompanyId", k.getSourceCompanyId() != null ? k.getSourceCompanyId().toString() : null);
        m.put("sourceType", k.getSourceType());
        m.put("category", k.getCategory());
        m.put("title", k.getTitle());
        m.put("content", k.getContent());
        m.put("version", k.getVersion());
        m.put("supersedesId", k.getSupersedesId() != null ? k.getSupersedesId().toString() : null);
        m.put("effectiveAt", k.getEffectiveAt() != null ? k.getEffectiveAt().toString() : null);
        m.put("expireAt", k.getExpireAt() != null ? k.getExpireAt().toString() : null);
        m.put("status", k.getStatus());
        m.put("embeddingStatus", k.getEmbeddingStatus());
        m.put("indexedAt", k.getIndexedAt() != null ? k.getIndexedAt().toString() : null);
        m.put("indexError", k.getIndexError());
        m.put("source", k.getSource());
        m.put("sourceTaskId", k.getSourceTaskId() != null ? k.getSourceTaskId().toString() : null);
        m.put("sourceDocument", k.getSourceDocument());
        m.put("evidence", k.getEvidence());
        m.put("generationJobId", k.getGenerationJobId() != null ? k.getGenerationJobId().toString() : null);
        m.put("generated", k.getGenerationJobId() != null);
        m.put("reviewedAt", k.getReviewedAt() != null ? k.getReviewedAt().toString() : null);
        m.put("reviewNote", k.getReviewNote());
        m.put("createdBy", k.getCreatedBy() != null ? k.getCreatedBy().toString() : null);
        m.put("createdAt", k.getCreatedAt() != null ? k.getCreatedAt().toString() : null);
        m.put("updatedAt", k.getUpdatedAt() != null ? k.getUpdatedAt().toString() : null);
        return m;
    }

    private Map<String, Object> toTaskMap(KnowledgeUploadTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId().toString());
        m.put("companyId", t.getCompanyId() != null ? t.getCompanyId().toString() : null);
        m.put("fileName", t.getFileName());
        m.put("status", t.getStatus());
        m.put("producedCount", t.getProducedCount());
        m.put("totalSections", t.getTotalSections());
        m.put("processedSections", t.getProcessedSections());
        m.put("failedSections", t.getFailedSections());
        m.put("sourceKind", t.getSourceKind());
        m.put("skippedCount", t.getSkippedCount());
        m.put("guideMessage", t.getGuideMessage());
        m.put("errorMessage", t.getErrorMessage());
        m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        return m;
    }
}
