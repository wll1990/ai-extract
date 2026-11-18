package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.PromptLoader;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ContentGenerationJob;
import com.aiextract.model.EvaluationDataset;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.EvaluationRun;
import com.aiextract.model.EvaluationRunItem;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.ContentGenerationJobRepository;
import com.aiextract.repository.EvaluationDatasetRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.EvaluationRunRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 内容生成 —— 知识/案例/评测题统一从真实信号起草。
 *
 * <p>原则：</p>
 * <ul>
 *   <li>只出草稿（pending/draft），绝不直接 active；</li>
 *   <li>每条草稿带 evidence（来源引用），审核台可追溯；</li>
 *   <li>HTTP 线程只做校验、信号收集、去重与建任务；AI 调用全部在 contentGenerationExecutor；</li>
 *   <li>同一场景同一信号指纹一天只生成一次（dedupeKey 防刷），多实例由部分唯一索引兜底。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentGenerationService {

    private static final String PROMPT_KNOWLEDGE = "content_knowledge_draft.md";
    private static final String PROMPT_CASE = "content_case_draft.md";
    private static final String PROMPT_EVAL = "content_eval_item.md";
    private static final String PROMPT_DERIVE = "content_derive.md";
    private static final int MAX_SIGNAL_CHARS = 3000;
    /** 超时未结束任务视为进程崩溃遗留，允许重新生成 */
    private static final int STALE_RUNNING_MINUTES = 30;

    private final ChatStreamAdapter chatStreamAdapter;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final KnowledgeEntryRepository knowledgeRepository;
    private final IndustryCaseRepository caseRepository;
    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationItemRepository itemRepository;
    private final EvaluationRunRepository runRepository;
    private final EvaluationRunItemRepository runItemRepository;
    private final ContentGenerationJobRepository jobRepository;
    private final ContentSignalService signalService;
    private final TrainingAccessService accessService;
    private final CompanyScopeService companyScopeService;
    private final ObjectProvider<ContentGenerationService> selfProvider;

    public record JobView(UUID id, String kind, String sourceType, String status,
                          int producedCount, String errorMessage, LocalDateTime createdAt,
                          LocalDateTime finishedAt) {
    }

    public record JobDetail(UUID id, String kind, String sourceType, String sourceRef,
                            String status, int producedCount, JsonNode result,
                            String errorMessage, LocalDateTime createdAt,
                            LocalDateTime updatedAt, LocalDateTime finishedAt) {
    }

    private record IssueCandidate(String scene, String question) {
    }

    // ==================== 知识 ====================

    /** 入队一条知识草稿任务，立即返回 jobId；AI 在后台线程执行。 */
    public UUID startKnowledgeGeneration(String sceneTag, List<String> signals) {
        UUID companyId = accessService.requireCompanyId();
        long pendingDrafts = knowledgeRepository
                .countByCompanyIdAndStatusAndGenerationJobIdIsNotNull(companyId, "pending");
        if (pendingDrafts > 0) {
            throw BusinessException.conflict("CONTENT_PENDING_REVIEW",
                    "还有 " + pendingDrafts + " 条 AI 知识草稿待审，请先处理后再生成新的");
        }
        String scene = normalizeScene(sceneTag);
        List<ContentSignalService.Signal> effective = collectSignals(scene, signals);
        String signalText = renderSignals(effective);
        String dedupe = "knowledge|" + scene + "|" + LocalDate.now() + "|" + fingerprint(signalText);
        ensureNotGeneratedToday(companyId, ContentGenerationJob.KIND_KNOWLEDGE, dedupe);

        UUID jobId = selfProvider.getObject().startJob(companyId,
                ContentGenerationJob.KIND_KNOWLEDGE, "knowledge_gap", scene, dedupe);
        selfProvider.getObject().runKnowledgeJob(jobId, companyId, scene, effective);
        return jobId;
    }

    @Async("contentGenerationExecutor")
    public void runKnowledgeJob(UUID jobId, UUID companyId, String scene,
                                List<ContentSignalService.Signal> signals) {
        try {
            KnowledgeEntry entry = draftKnowledge(jobId, companyId, scene, signals);
            selfProvider.getObject().finishJob(jobId, 1,
                    idsResult(List.of(entry.getId()), List.of()));
        } catch (Exception e) {
            log.warn("知识草稿生成失败 job={}", jobId, e);
            selfProvider.getObject().failJob(jobId, describe(e));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public KnowledgeEntry persistKnowledge(UUID jobId, UUID companyId, String scene,
                                           JsonNode draft, List<ContentSignalService.Signal> signals) {
        String title = text(draft, "title");
        String content = text(draft, "content");
        String category = text(draft, "category");
        if (title == null || content == null || category == null) {
            throw new BusinessException(502, "知识草稿缺少标题/正文/分类");
        }
        if (!KnowledgeEntry.isValidCategory(category)) {
            throw new BusinessException(502, "知识草稿分类非法：" + category);
        }
        if (title.length() > 255) {
            title = title.substring(0, 255);
        }
        if (knowledgeRepository.existsByCompanyScopeAndLowerTitle(companyId, title.toLowerCase())) {
            throw BusinessException.conflict("CONTENT_DUPLICATE", "同标题知识已存在，请先审核已有条目");
        }
        double confidence = draft.path("confidence").asDouble(0.5);
        if (confidence < 0.3) {
            throw new BusinessException(409, "信号不足以生成可信知识："
                    + draft.path("reason").asText("请先补充素材"));
        }
        LocalDateTime now = LocalDateTime.now();
        KnowledgeEntry entry = KnowledgeEntry.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .sourceType(KnowledgeEntry.SOURCE_TYPE_TENANT)
                .category(category)
                .title(title)
                .content(content)
                .version(1)
                .status(KnowledgeEntry.STATUS_PENDING)
                .origin(KnowledgeEntry.ORIGIN_INTERNAL)
                .source(text(draft, "source") == null ? "AI 依据真实缺口起草" : text(draft, "source"))
                .evidence(evidenceJson("knowledge_gap", scene, signals))
                .generationJobId(jobId)
                .createdBy(TokenContext.get())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return knowledgeRepository.save(entry);
    }

    // ==================== 案例 ====================

    public UUID startCaseGeneration(String sceneTag, List<String> signals, String conversation) {
        UUID companyId = accessService.requireCompanyId();
        long pendingDrafts = caseRepository
                .countByCompanyIdAndStatusAndGenerationJobIdIsNotNull(companyId, "pending");
        if (pendingDrafts > 0) {
            throw BusinessException.conflict("CONTENT_PENDING_REVIEW",
                    "还有 " + pendingDrafts + " 条 AI 案例草稿待审，请先处理后再生成新的");
        }
        String scene = normalizeScene(sceneTag);
        List<ContentSignalService.Signal> effective = collectSignals(scene, signals);
        String signalText = renderSignals(effective);
        String dedupe = "case|" + scene + "|" + LocalDate.now() + "|" + fingerprint(signalText);
        ensureNotGeneratedToday(companyId, ContentGenerationJob.KIND_CASE, dedupe);

        UUID jobId = selfProvider.getObject().startJob(companyId,
                ContentGenerationJob.KIND_CASE, "conversation", scene, dedupe);
        selfProvider.getObject().runCaseJob(jobId, companyId, scene, effective, conversation);
        return jobId;
    }

    @Async("contentGenerationExecutor")
    public void runCaseJob(UUID jobId, UUID companyId, String scene,
                           List<ContentSignalService.Signal> signals, String conversation) {
        try {
            Map<String, String> vars = new HashMap<>();
            vars.put("scene_tag", scene);
            vars.put("signals", renderSignals(signals));
            vars.put("conversation", conversation == null || conversation.isBlank()
                    ? "（无对话，只有信号）" : truncate(conversation, MAX_SIGNAL_CHARS));
            JsonNode draft = parse(chatStreamAdapter.chat(promptLoader.format(PROMPT_CASE, vars)),
                    "案例草稿");
            IndustryCase entry = selfProvider.getObject().persistCase(jobId, companyId, scene,
                    draft, signals);
            selfProvider.getObject().finishJob(jobId, 1,
                    idsResult(List.of(entry.getId()), List.of()));
        } catch (Exception e) {
            log.warn("案例草稿生成失败 job={}", jobId, e);
            selfProvider.getObject().failJob(jobId, describe(e));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public IndustryCase persistCase(UUID jobId, UUID companyId, String scene,
                                    JsonNode draft, List<ContentSignalService.Signal> signals) {
        String title = text(draft, "title");
        String result = text(draft, "result");
        if (title == null) {
            throw new BusinessException(502, "案例草稿缺少标题");
        }
        if (!IndustryCase.isValidResult(result)) {
            throw new BusinessException(502, "案例结果非法（必须是 win/loss/delay）");
        }
        if (caseRepository.existsByCompanyScopeAndLowerTitle(companyId, title.toLowerCase())) {
            throw BusinessException.conflict("CONTENT_DUPLICATE", "同标题案例已存在");
        }
        double confidence = draft.path("confidence").asDouble(0.5);
        if (confidence < 0.3) {
            throw new BusinessException(409, "信号不足以生成可信案例："
                    + draft.path("reason").asText("请先补充对话或复盘"));
        }
        LocalDateTime now = LocalDateTime.now();
        IndustryCase entry = IndustryCase.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .title(truncate(title, 255))
                .customerProfile(profileJson(draft.get("customerProfile")))
                .funnelStage(truncate(text(draft, "funnelStage"), 20))
                .sceneTag(truncate(scene, 50))
                .dialogueQuote(quotesJson(draft.get("dialogueQuote")))
                .objection(text(draft, "objection"))
                .emotionChange(text(draft, "emotionChange"))
                .keyActions(text(draft, "keyActions"))
                .scripts(text(draft, "scripts"))
                .advanceNodes(text(draft, "advanceNodes"))
                .result(result)
                .reusablePoints(text(draft, "reusablePoints"))
                .failurePoints(text(draft, "failurePoints"))
                .source(text(draft, "source") == null ? "AI 依据真实信号起草" : text(draft, "source"))
                .origin(IndustryCase.ORIGIN_INTERNAL)
                .evidence(evidenceJson("conversation", scene, signals))
                .generationJobId(jobId)
                .contributorId(TokenContext.get())
                .version(1)
                .status(IndustryCase.STATUS_PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        return caseRepository.save(entry);
    }

    // ==================== 评测题 ====================

    public UUID startEvalItemGeneration(UUID datasetId, String sceneTag, int limit) {
        UUID companyId = accessService.requireCompanyId();
        companyScopeService.assertDatasetReadable(datasetId);
        EvaluationDataset dataset = datasetRepository.findById(datasetId)
                .orElseThrow(() -> new BusinessException(404, "题集不存在"));
        if (!EvaluationDataset.STATUS_DRAFT.equals(dataset.getStatus())) {
            throw new BusinessException(409, "题集已提交审核或已发布，不能新增题目；请先退回草稿或新建版本");
        }
        String scene = normalizeScene(sceneTag);
        List<ContentSignalService.Signal> signals = collectSignals(scene, null);
        if (signals.isEmpty()) {
            throw new BusinessException(409, "还没有可用的真实失败信号，先积累缺口/错题再出题");
        }
        String dedupe = "eval|" + datasetId + "|" + scene + "|" + LocalDate.now();
        ensureNotGeneratedToday(companyId, ContentGenerationJob.KIND_EVAL_ITEM, dedupe);

        UUID jobId = selfProvider.getObject().startJob(companyId,
                ContentGenerationJob.KIND_EVAL_ITEM, "eval_issue", scene, dedupe);
        selfProvider.getObject().runEvalItemJob(jobId, companyId, datasetId, scene, signals,
                Math.max(1, Math.min(limit <= 0 ? 5 : limit, 5)));
        return jobId;
    }

    @Async("contentGenerationExecutor")
    public void runEvalItemJob(UUID jobId, UUID companyId, UUID datasetId, String scene,
                               List<ContentSignalService.Signal> signals, int limit) {
        try {
            EvaluationDataset dataset = datasetRepository.findById(datasetId)
                    .orElseThrow(() -> new BusinessException(404, "题集不存在"));
            Map<String, String> vars = new HashMap<>();
            vars.put("scene_tag", scene);
            vars.put("signals", renderSignals(signals));
            JsonNode root = parse(chatStreamAdapter.chat(promptLoader.format(PROMPT_EVAL, vars)),
                    "评测题");
            List<EvaluationItem> saved = selfProvider.getObject().persistEvalItems(
                    jobId, companyId, dataset, scene, root.path("items"), signals, limit);
            selfProvider.getObject().finishJob(jobId, saved.size(),
                    idsResult(saved.stream().map(EvaluationItem::getId).toList(), List.of()));
        } catch (Exception e) {
            log.warn("评测题生成失败 job={}", jobId, e);
            selfProvider.getObject().failJob(jobId, describe(e));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public List<EvaluationItem> persistEvalItems(UUID jobId, UUID companyId,
                                                 EvaluationDataset dataset, String scene,
                                                 JsonNode itemsNode,
                                                 List<ContentSignalService.Signal> signals, int limit) {
        if (itemsNode == null || !itemsNode.isArray() || itemsNode.isEmpty()) {
            throw new BusinessException(502, "AI 没有生成可用题目");
        }
        int version = dataset.getCurrentVersion() == null ? 1 : dataset.getCurrentVersion();
        List<String> existing = itemRepository.findQuestionsByDatasetIdAndVersionAndStatusIn(
                dataset.getId(), version, List.of(EvaluationItem.STATUS_ACTIVE,
                        EvaluationItem.STATUS_DRAFT));
        // 必须可变：循环里要把本批次新题也加入去重基线
        List<String> existingNorm = new ArrayList<>(existing.stream()
                .map(this::normalizeQuestion).toList());
        int order = itemRepository.findMaxOrderNo(dataset.getId(), version);
        List<EvaluationItem> saved = new ArrayList<>();
        for (JsonNode node : itemsNode) {
            if (saved.size() >= limit) {
                break;
            }
            String question = text(node, "question");
            JsonNode points = node.get("expectedPoints");
            if (question == null || points == null || !points.isArray() || points.isEmpty()) {
                continue;
            }
            if (existingNorm.contains(normalizeQuestion(question))) {
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            saved.add(itemRepository.save(EvaluationItem.builder()
                    .id(UUID.randomUUID())
                    .companyId(companyId)
                    .datasetId(dataset.getId())
                    .version(version)
                    .question(question)
                    .context(text(node, "context"))
                    .expectedPoints(json(points))
                    .forbiddenPoints(json(node.path("forbiddenPoints")))
                    .citationRequirement(null)
                    .sceneTag(truncate(scene, 50))
                    .goldBasis(text(node, "goldBasis"))
                    .scoringRule(jsonOrNull(node.get("scoringRule")))
                    .orderNo(++order)
                    // AI 只出草稿，人工审核后才进入 active
                    .status(EvaluationItem.STATUS_DRAFT)
                    .generated(true)
                    .evidence(evidenceJson("eval_issue", scene, signals))
                    .generationJobId(jobId)
                    .createdBy(TokenContext.get())
                    .createdAt(now)
                    .updatedAt(now)
                    .build()));
            existingNorm.add(normalizeQuestion(question));
        }
        if (saved.isEmpty()) {
            throw new BusinessException(409, "生成内容与已有题目重复，未新增");
        }
        dataset.setItemCount((int) itemRepository.countByDatasetIdAndVersionAndStatus(
                dataset.getId(), version, EvaluationItem.STATUS_ACTIVE));
        dataset.setUpdatedAt(LocalDateTime.now());
        datasetRepository.save(dataset);
        return saved;
    }

    // ==================== 错题回流 ====================

    public UUID startPromoteIssues(UUID runId) {
        UUID companyId = accessService.requireCompanyId();
        companyScopeService.assertRunReadable(runId);
        EvaluationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(404, "评测批次不存在"));
        int version = run.getDatasetVersion() == null ? 1 : run.getDatasetVersion();
        Map<UUID, EvaluationItem> itemMap = new HashMap<>();
        itemRepository.findByDatasetIdAndVersionOrderByOrderNoAsc(
                        run.getDatasetId(), version, PageRequest.of(0, 500))
                .forEach(i -> itemMap.put(i.getId(), i));

        List<IssueCandidate> candidates = new ArrayList<>();
        for (EvaluationRunItem row : runItemRepository.findLatestAttempts(runId)) {
            boolean failed = EvaluationRunItem.VERDICT_FAIL.equals(row.getVerdict())
                    || EvaluationRunItem.STATUS_ERROR.equals(row.getStatus());
            if (!failed) {
                continue;
            }
            EvaluationItem item = itemMap.get(row.getItemId());
            String question = item != null && item.getQuestion() != null && !item.getQuestion().isBlank()
                    ? item.getQuestion() : "题目 " + row.getItemId();
            String scene = item != null && item.getSceneTag() != null && !item.getSceneTag().isBlank()
                    ? item.getSceneTag() : "评测错题";
            candidates.add(new IssueCandidate(scene, question));
            if (candidates.size() >= 3) {
                break;
            }
        }
        if (candidates.isEmpty()) {
            throw new BusinessException(409, "本批次没有未通过/执行失败的题目");
        }
        String dedupe = "promote|" + runId + "|" + LocalDate.now();
        ensureNotGeneratedToday(companyId, ContentGenerationJob.KIND_PROMOTE_ISSUES, dedupe);
        UUID jobId = selfProvider.getObject().startJob(companyId,
                ContentGenerationJob.KIND_PROMOTE_ISSUES, "eval_issue", runId.toString(), dedupe);
        selfProvider.getObject().runPromoteIssuesJob(jobId, companyId, candidates);
        return jobId;
    }

    @Async("contentGenerationExecutor")
    public void runPromoteIssuesJob(UUID jobId, UUID companyId, List<IssueCandidate> candidates) {
        List<UUID> created = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        try {
            for (IssueCandidate candidate : candidates) {
                try {
                    KnowledgeEntry entry = draftKnowledge(jobId, companyId, candidate.scene(),
                            List.of(new ContentSignalService.Signal("eval_issue", candidate.scene(),
                                    "评测失败题：" + candidate.question(), null)));
                    created.add(entry.getId());
                } catch (Exception e) {
                    errors.add(candidate.question() + ": " + describe(e));
                }
            }
            selfProvider.getObject().finishJob(jobId, created.size(), idsResult(created, errors));
        } catch (Exception e) {
            log.warn("错题回流失败 job={}", jobId, e);
            selfProvider.getObject().failJob(jobId, describe(e));
        }
    }

    // ==================== AI 派生 ====================

    public UUID startDerive(String sourceType, UUID sourceId) {
        UUID companyId = accessService.requireCompanyId();
        String title;
        String material;
        if ("case".equals(sourceType)) {
            IndustryCase c = companyScopeService.requireCaseReadable(sourceId);
            title = c.getTitle();
            material = String.join("\n", nvl(c.getCustomerProfile()), nvl(c.getObjection()),
                    nvl(c.getKeyActions()), nvl(c.getScripts()), nvl(c.getReusablePoints()),
                    nvl(c.getFailurePoints()));
        } else {
            KnowledgeEntry k = companyScopeService.requireKnowledgeReadable(sourceId);
            title = k.getTitle();
            material = nvl(k.getContent());
        }
        UUID jobId = selfProvider.getObject().startJob(companyId,
                ContentGenerationJob.KIND_DERIVE, "case".equals(sourceType) ? "case" : "knowledge",
                sourceId.toString(), null);
        selfProvider.getObject().runDeriveJob(jobId, sourceType, title, material);
        return jobId;
    }

    @Async("contentGenerationExecutor")
    public void runDeriveJob(UUID jobId, String sourceType, String title, String material) {
        try {
            Map<String, String> vars = new HashMap<>();
            boolean fromCase = "case".equals(sourceType);
            vars.put("source_type", fromCase ? "案例" : "知识");
            vars.put("title", title);
            vars.put("material", truncate(material, 4000));
            vars.put("derive_focus", fromCase
                    ? "这是案例：先提炼 3-5 条可复用知识卡（放在 faqs 字段：question 写「场景+问题」，"
                    + "answer 写可复用事实/打法），再生成评测题与场景微练。"
                    : "这是知识条目：只生成评测题与场景微练；faqs 必须为空数组。");
            ObjectNode result = (ObjectNode) parse(chatStreamAdapter.chat(
                    promptLoader.format(PROMPT_DERIVE, vars)), "派生内容");
            // 知识来源不生成 FAQ：回灌知识库只会制造重复条目，价值低
            if (!fromCase) {
                result.putArray("faqs");
            }
            selfProvider.getObject().finishJob(jobId, 1, result);
        } catch (Exception e) {
            log.warn("内容派生失败 job={}", jobId, e);
            selfProvider.getObject().failJob(jobId, describe(e));
        }
    }

    // ==================== 任务留痕 ====================

    /**
     * 建任务：先回收本企业超时 running 任务，再插入并立即 flush。
     * 并发重复由 V96 的部分唯一索引兜底，冲突返回 409。
     */
    @Transactional(rollbackFor = Exception.class)
    public UUID startJob(UUID companyId, String kind, String sourceType,
                         String sourceRef, String dedupeKey) {
        LocalDateTime now = LocalDateTime.now();
        jobRepository.findByCompanyIdAndStatusAndCreatedAtBefore(
                        companyId, ContentGenerationJob.STATUS_RUNNING,
                        now.minusMinutes(STALE_RUNNING_MINUTES))
                .forEach(stale -> {
                    stale.setStatus(ContentGenerationJob.STATUS_FAILED);
                    stale.setErrorMessage("任务超时中断");
                    stale.setFinishedAt(now);
                    stale.setUpdatedAt(now);
                    jobRepository.save(stale);
                });

        ContentGenerationJob job = ContentGenerationJob.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .kind(kind)
                .sourceType(sourceType)
                .sourceRef(truncate(sourceRef, 128))
                .status(ContentGenerationJob.STATUS_RUNNING)
                .producedCount(0)
                .dedupeKey(dedupeKey)
                .requestedBy(TokenContext.get())
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            jobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException e) {
            throw BusinessException.conflict("CONTENT_ALREADY_RUNNING",
                    "同类内容的生成任务正在进行中，请稍后刷新查看");
        }
        return job.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void finishJob(UUID jobId, int produced, JsonNode result) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(ContentGenerationJob.STATUS_DONE);
            job.setProducedCount(produced);
            job.setResult(json(result));
            job.setFinishedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    /** 兼容旧调用：只记录一句话说明（无结构化结果）。 */
    @Transactional(rollbackFor = Exception.class)
    public void finishJob(UUID jobId, int produced, String detail) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(ContentGenerationJob.STATUS_DONE);
            job.setProducedCount(produced);
            job.setDetail(toJson(Map.of("note", detail)));
            job.setFinishedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    @Transactional(rollbackFor = Exception.class)
    public void failJob(UUID jobId, String message) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(ContentGenerationJob.STATUS_FAILED);
            job.setErrorMessage(truncate(message, 1000));
            job.setFinishedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());
            jobRepository.save(job);
        });
    }

    public JobDetail jobDetail(UUID jobId) {
        UUID companyId = accessService.requireCompanyId();
        ContentGenerationJob job = jobRepository.findByIdAndCompanyId(jobId, companyId)
                .orElseThrow(() -> new BusinessException(404, "生成任务不存在"));
        return new JobDetail(job.getId(), job.getKind(), job.getSourceType(), job.getSourceRef(),
                job.getStatus(), job.getProducedCount() == null ? 0 : job.getProducedCount(),
                readJson(job.getResult()), job.getErrorMessage(), job.getCreatedAt(),
                job.getUpdatedAt(), job.getFinishedAt());
    }

    public List<JobView> recentJobs(int limit) {
        UUID companyId = accessService.requireCompanyId();
        return jobRepository.findByCompanyIdOrderByCreatedAtDesc(companyId,
                        PageRequest.of(0, Math.max(1, Math.min(limit, 50))))
                .getContent().stream()
                .map(j -> new JobView(j.getId(), j.getKind(), j.getSourceType(), j.getStatus(),
                        j.getProducedCount() == null ? 0 : j.getProducedCount(),
                        j.getErrorMessage(), j.getCreatedAt(), j.getFinishedAt()))
                .toList();
    }

    // ==================== 内部 ====================

    /** 单个知识草稿：AI 调用 + 短事务落库（供知识/错题回流复用） */
    private KnowledgeEntry draftKnowledge(UUID jobId, UUID companyId, String scene,
                                          List<ContentSignalService.Signal> signals) {
        Map<String, String> vars = new HashMap<>();
        vars.put("scene_tag", scene);
        vars.put("signals", renderSignals(signals));
        vars.put("company_context", "企业私有内容，仅本企业可见；不要引用其他企业资料。");
        JsonNode draft = parse(chatStreamAdapter.chat(
                promptLoader.format(PROMPT_KNOWLEDGE, vars)), "知识草稿");
        return selfProvider.getObject().persistKnowledge(jobId, companyId, scene, draft, signals);
    }

    private void ensureNotGeneratedToday(UUID companyId, String kind, String dedupeKey) {
        jobRepository.findFirstByCompanyIdAndKindAndDedupeKeyOrderByCreatedAtDesc(
                companyId, kind, dedupeKey).ifPresent(job -> {
                    if (ContentGenerationJob.STATUS_DONE.equals(job.getStatus())) {
                        throw BusinessException.conflict("CONTENT_ALREADY_GENERATED",
                                "该场景今天已生成过草稿，请先审核已有内容，明天再试");
                    }
                });
    }

    private List<ContentSignalService.Signal> collectSignals(String scene, List<String> explicit) {
        if (explicit != null && !explicit.isEmpty()) {
            return dedupeSignals(explicit.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> new ContentSignalService.Signal(null, scene, s.trim(), null))
                    .toList());
        }
        List<ContentSignalService.Signal> recent = signalService.recentSignals(50);
        List<ContentSignalService.Signal> matched = recent.stream()
                .filter(s -> scene.equals(s.sceneTag()))
                .limit(10)
                .toList();
        // 场景没有精确命中时退回最近 10 条（与旧行为一致：缺口之外还会带上实战/评测信号）
        return dedupeSignals(matched.isEmpty() ? signalService.recentSignals(10) : matched);
    }

    /** 生成依据按文本去重：同一条失败题/缺口只保留第一次出现，避免证据面板刷屏。 */
    private List<ContentSignalService.Signal> dedupeSignals(List<ContentSignalService.Signal> signals) {
        Map<String, ContentSignalService.Signal> unique = new LinkedHashMap<>();
        for (ContentSignalService.Signal signal : signals) {
            String key = ContentSignalService.normalizeText(signal.text());
            if (!key.isEmpty()) {
                unique.putIfAbsent(key, signal);
            }
        }
        return List.copyOf(unique.values());
    }

    private JsonNode idsResult(List<UUID> ids, List<String> errors) {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode arr = node.putArray("ids");
        ids.forEach(id -> arr.add(id.toString()));
        ArrayNode err = node.putArray("errors");
        errors.forEach(err::add);
        return node;
    }

    private JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String describe(Exception e) {
        return e instanceof BusinessException be ? be.getMessage() : "生成失败：" + e.getMessage();
    }

    private JsonNode parse(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(502, label + "返回为空");
        }
        String cleaned = raw.trim().replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (node == null || !node.isObject()) {
                throw new BusinessException(502, label + "返回的不是 JSON 对象");
            }
            return node;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, label + "返回无法解析：" + e.getMessage());
        }
    }

    private String evidenceJson(String type, String scene, List<ContentSignalService.Signal> signals) {
        ArrayNode arr = objectMapper.createArrayNode();
        for (ContentSignalService.Signal signal : dedupeSignals(
                signals == null ? List.of() : signals)) {
            ObjectNode item = arr.addObject();
            item.put("type", signal.type() == null || signal.type().isBlank() ? type : signal.type());
            item.put("sceneTag", signal.sceneTag() == null || signal.sceneTag().isBlank()
                    ? scene : signal.sceneTag());
            item.put("text", truncate(signal.text(), 300));
        }
        return json(arr);
    }

    private String renderSignals(List<ContentSignalService.Signal> signals) {
        if (signals == null || signals.isEmpty()) {
            return "（无信号）";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentSignalService.Signal signal : signals) {
            sb.append("- ").append(truncate(signal.text(), 300)).append('\n');
        }
        return truncate(sb.toString(), MAX_SIGNAL_CHARS);
    }

    private String normalizeScene(String scene) {
        return scene == null || scene.isBlank() ? "未分类" : truncate(scene.trim(), 50);
    }

    /** 信号内容指纹：同场景同信号一天只生成一次，不同信号可分别生成 */
    private String fingerprint(String signalText) {
        return Integer.toHexString(signalText == null ? 0 : signalText.hashCode());
    }

    private String normalizeQuestion(String q) {
        return q == null ? "" : q.replaceAll("\\s+", "").toLowerCase();
    }

    private String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        String value = node.path(field).asText("");
        return value.isBlank() ? null : value.trim();
    }

    private String json(JsonNode node) {
        try {
            return node == null || node.isMissingNode() || node.isNull()
                    ? null : objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new BusinessException(500, "JSON 序列化失败");
        }
    }

    private String jsonOrNull(JsonNode node) {
        return json(node);
    }

    /** 客户画像 JSONB：对象/数组直接序列化，纯文本包成 {"描述": "..."}，避免 JSONB 写入失败 */
    private String profileJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject() || node.isArray()) {
            return json(node);
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : toJson(Map.of("描述", truncate(value, 500)));
    }

    /** 客户原话 JSONB 数组：数组直接序列化，单条文本包成 ["..."] */
    private String quotesJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isArray()) {
            return json(node);
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : toJson(List.of(truncate(value, 500)));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }
}
