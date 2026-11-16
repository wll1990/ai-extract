package com.aiextract.service;

import com.aiextract.common.AiErrorMapper;
import com.aiextract.common.SourceText;
import com.aiextract.config.PromptLoader;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.model.KnowledgeUploadDraft;
import com.aiextract.model.KnowledgeUploadTask;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.KnowledgeUploadDraftRepository;
import com.aiextract.repository.KnowledgeUploadTaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识管道清洗服务 —— 从文档提取「事实型知识」候选（独立管道，不复用经验管道）。
 *
 * <p>与经验管道 {@link MaterialCleaningService} 严格隔离：无销售对话准入、无对抗验证、
 * 无错别字修正（不复用 {@code TextNormalizer}）、无句子分块。只复用通用文档格式清洗
 * {@code MaterialNoiseCleaner.cleanFormatNoise(text,"document")} 与通用文件解析
 * {@link FileParseService}。</p>
 *
 * <p>分块采用结构感知：{@link DocumentStructureParser} 识别章节层级，按「节」分块并带
 * 标题链上下文，适合整本书/长文档。AI 提取分钟级，走 {@code @Async("cleanExecutor")}。</p>
 *
 * <p>章节级进度 + 断点续传（T16）：异步萃取按「节」逐节提交，每节落库候选 + 推进
 * {@code processed_sections} 检查点；中断后调用 {@link #retryUpload(UUID)} 从检查点续传，
 * 不重做已完成章节。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeCleaningService {

    /** 自注入代理：AI 调用在事务外，落库走 self 短事务 */
    @Autowired
    @Lazy
    private KnowledgeCleaningService self;

    private final KnowledgeEntryRepository knowledgeRepository;
    private final KnowledgeUploadTaskRepository uploadTaskRepository;
    private final KnowledgeUploadDraftRepository draftRepository;
    private final MaterialNoiseCleaner noiseCleaner;
    private final ChatStreamAdapter chatStreamAdapter;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final FileParseService fileParseService;
    private final FileStorageService fileStorageService;

    /**
     * 正在萃取的任务集合（进程内）：区分「本 JVM 仍在跑」与「上次崩溃遗留的孤儿任务」。
     * 崩溃后 JVM 死亡、集合随之清空，故重启后 retry 能正确识别孤儿并续传；不能用 DB status
     * 判断（孤儿任务 status 仍停在 extracting）。
     */
    private final Set<UUID> extractingTasks = ConcurrentHashMap.newKeySet();

    /** 书籍模式单次上传最多产出的知识卡片数，超出提示拆书上传，避免一本书灌爆审核队列 */
    public static final int MAX_BOOK_CANDIDATES = 200;

    /** 解析后正文上限（默认 50 万字，约 500 页），超出直接拒绝并提示按章节拆分 */
    @Value("${app.knowledge.max-text-chars:500000}")
    private int maxTextChars;

    // ==================== 纯逻辑（可单测） ====================

    /**
     * 轻量归一化：仅整理空行与首尾空白，不含错别字修正。
     * 关键约束：不复用 {@code TextNormalizer}（其「超标→招标」类修正会破坏事实）。
     */
    public static String normalizeLight(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\n{3,}", "\n\n").trim();
    }

    // ==================== 提取 ====================

    /**
     * 从文档全文提取知识候选：格式清洗 → 轻量归一化 → 结构解析按节分块 → 分批 AI 提取 → 按标题去重。
     * 每个候选的 source 记「章·节」标题链。无 @Transactional（AI 调用在事务外）。
     */
    public List<KnowledgeCandidate> extractFromDocument(String text, String domain) {
        return extractFromDocument(text, domain, UploadContentMode.INTERNAL, null);
    }

    /** 同上，可指定素材类型：书籍走方法论卡片提示词。 */
    public List<KnowledgeCandidate> extractFromDocument(String text, String domain,
                                                        String sourceKind, String bookTitle) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String cleaned = noiseCleaner.cleanFormatNoise(text, "document");
        String normalized = PiiMasker.mask(normalizeLight(cleaned));
        List<DocumentStructureParser.Section> sections = DocumentStructureParser.parseSections(normalized);
        boolean bookMode = UploadContentMode.isBook(sourceKind);
        List<KnowledgeCandidate> all = new ArrayList<>();
        for (DocumentStructureParser.Section section : sections) {
            all.addAll(extractSection(section, domain, bookMode, bookTitle).candidates());
        }
        return deduplicate(all);
    }

    /**
     * 异步上传萃取（可断点续传）：解析文件 → 按节逐节提取并提交检查点 → 完成后标记 done。
     * 由 Controller 在存文件、建 task(extracting) 后触发，立即返回 taskId。
     *
     * <p>续传语义：起始位置取自 task.processedSections，跳过已提交章节；每节提交后
     * processedSections 前移，进程中断也不会重复落库已完成章节的候选。</p>
     */
    @Async("cleanExecutor")
    public void extractUploadAsync(UUID taskId, UUID companyId, UUID operatorId,
                                   String filePath, String fileName) {
        if (!extractingTasks.add(taskId)) {
            log.warn("知识上传任务已在萃取中，跳过重复触发 taskId={}", taskId);
            return;
        }
        try {
            int resumeFrom = uploadTaskRepository.findById(taskId)
                    .map(t -> t.getProcessedSections() == null ? 0 : t.getProcessedSections())
                    .orElse(0);
            String text = fileParseService.parseToText(filePath, fileName);
            if (text == null || text.isBlank()) {
                self.markTaskFailed(taskId, "文件解析失败或内容为空");
                return;
            }
            if (text.length() > maxTextChars) {
                self.markTaskFailed(taskId, "文档解析后 " + text.length() + " 字，超过单次上限 "
                        + maxTextChars + " 字（约 500 页）。请按章节或产品线拆分后分批上传；单文件最大 50MB。");
                return;
            }
            String cleaned = noiseCleaner.cleanFormatNoise(text, "document");
            // 进入模型与落库前统一脱敏：手机号/身份证/银行卡/座机/邮箱
            String normalized = PiiMasker.mask(normalizeLight(cleaned));
            List<DocumentStructureParser.Section> sections = DocumentStructureParser.parseSections(normalized);
            if (sections.isEmpty()) {
                self.markTaskFailed(taskId, "文档未提取到有效知识条目");
                return;
            }
            // 素材类型：显式选择优先；未选择但明显是整本书时自动切到书籍模式
            String declared = uploadTaskRepository.findById(taskId)
                    .map(KnowledgeUploadTask::getSourceKind).orElse(UploadContentMode.INTERNAL);
            boolean bookMode = UploadContentMode.isBook(declared)
                    || UploadContentMode.detectBookLike(fileName, normalized, sections.size());
            if (bookMode && !UploadContentMode.isBook(declared)) {
                self.setTaskSourceKind(taskId, UploadContentMode.BOOK);
                self.setTaskGuide(taskId,
                        "检测到书籍/课程版式，已按「方法论卡片」模式提炼：只保留可独立检索的观点，"
                                + "自动丢弃版权页、目录、序言和叙事段落。");
            }
            String bookTitle = bookMode ? stripExtension(fileName) : null;
            self.setTaskTotal(taskId, sections.size());
            int resume = Math.min(resumeFrom, sections.size());
            for (int i = resume; i < sections.size(); i++) {
                SectionResult result = extractSection(sections.get(i), null, bookMode, bookTitle);
                self.commitSection(taskId, companyId, operatorId,
                        result.candidates(), i + 1, result.failed() ? 1 : 0, bookMode, bookTitle);
            }
            self.markTaskDone(taskId);
        } catch (Exception e) {
            log.error("知识上传萃取失败 taskId={}: {}", taskId, e.getMessage(), e);
            self.markTaskFailed(taskId, AiErrorMapper.describe(e));
        } finally {
            extractingTasks.remove(taskId);
        }
    }

    // ==================== 落库 / 进度（经 self 代理，短事务） ====================

    /** 单节萃取结果：候选 + 是否发生过 chunk 级失败（用于页面提示部分失败） */
    private record SectionResult(List<KnowledgeCandidate> candidates, boolean failed) {
    }

    /** 提取单个节的候选（该节所有 chunk 提取后去重），是断点续传的最小提交单元。 */
    private SectionResult extractSection(DocumentStructureParser.Section section, String domain,
                                         boolean bookMode, String bookTitle) {
        List<KnowledgeCandidate> all = new ArrayList<>();
        boolean failed = false;
        for (String chunk : DocumentStructureParser.splitSection(section.content())) {
            try {
                all.addAll(extractChunk(chunk, section.chain(), domain, bookMode, bookTitle));
            } catch (Exception e) {
                // 单块失败不再静默：记录标记，任务继续处理后续章节
                failed = true;
                log.warn("知识块提取失败（章节将计入失败数）: {}", e.getMessage());
            }
        }
        return new SectionResult(deduplicate(all), failed);
    }

    /**
     * 提交一个节的候选并推进检查点（原子）：落库候选 + producedCount 累加 + processedSections 前移。
     * 非法分类跳过，返回本节落库条数。
     */
    @Transactional(rollbackFor = Exception.class)
    public int commitSection(UUID taskId, UUID companyId, UUID operatorId,
                             List<KnowledgeCandidate> candidates, int processedSections, int failedDelta,
                             boolean bookMode, String bookTitle) {
        // KN-06：先落草案，管理端预览后批量采纳，避免「萃取完直接进待审列表」无法整批处理
        KnowledgeUploadTask task = uploadTaskRepository.findById(taskId).orElse(null);
        int existing = task == null || task.getProducedCount() == null ? 0 : task.getProducedCount();
        int count = 0;
        int skipped = 0;
        boolean capHit = false;
        for (KnowledgeCandidate c : candidates) {
            // 模型输出兜底脱敏：书籍对外使用强脱敏，内部资料用标准脱敏
            java.util.function.Function<String, String> masker =
                    bookMode ? PiiMasker::maskStrict : PiiMasker::mask;
            c = new KnowledgeCandidate(masker.apply(c.title()), c.category(),
                    masker.apply(c.content()), masker.apply(c.source()));
            if (!KnowledgeEntry.isValidCategory(c.category())) {
                log.warn("跳过非法分类知识候选 title={} category={}", c.title(), c.category());
                skipped++;
                continue;
            }
            if (bookMode && existing + count >= MAX_BOOK_CANDIDATES) {
                capHit = true;
                skipped++;
                continue;
            }
            if (isDuplicateCandidate(taskId, companyId, c.title())) {
                log.info("跳过重复知识候选 title={}", c.title());
                skipped++;
                continue;
            }
            // 来源统一带文档名/书名，正文只保留章节链会导致无法追溯原始文档
            String documentName = bookMode && bookTitle != null && !bookTitle.isBlank()
                    ? bookTitle
                    : (task == null ? null : task.getFileName());
            String source = SourceText.compose(documentName, c.source());
            c = new KnowledgeCandidate(c.title(), c.category(), c.content(), source);
            ContentQualityGate.QualityReport report = ContentQualityGate.checkKnowledge(
                    c.title(), c.content(), c.category(), source, bookMode);
            draftRepository.save(KnowledgeUploadDraft.builder()
                    .id(UUID.randomUUID())
                    .taskId(taskId)
                    .sectionNo(processedSections)
                    .title(c.title())
                    .content(c.content())
                    .category(c.category())
                    .source(source)
                    .qualityScore(report.score())
                    .qualityIssues(report.issues().isEmpty() ? null : report.issueText())
                    .qualityWarnings(report.warnings().isEmpty() ? null : report.warningText())
                    .status(KnowledgeUploadDraft.STATUS_PENDING)
                    .createdAt(LocalDateTime.now())
                    .build());
            count++;
        }
        final int added = count;
        final int skippedDelta = skipped;
        final boolean cap = capHit;
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            t.setProducedCount(t.getProducedCount() + added);
            t.setSkippedCount((t.getSkippedCount() == null ? 0 : t.getSkippedCount()) + skippedDelta);
            t.setProcessedSections(processedSections);
            if (failedDelta > 0) {
                t.setFailedSections((t.getFailedSections() == null ? 0 : t.getFailedSections()) + failedDelta);
            }
            if (cap) {
                t.setGuideMessage(appendGuide(t.getGuideMessage(),
                        "单次上传最多产出 " + MAX_BOOK_CANDIDATES + " 条知识卡片，超出部分已跳过；建议按主题拆分后再上传。"));
            }
            uploadTaskRepository.save(t);
        });
        return count;
    }

    /** 同任务内 / 同企业既有知识按标题查重，避免同一观点反复入库 */
    private boolean isDuplicateCandidate(UUID taskId, UUID companyId, String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        String normalized = title.trim().toLowerCase();
        if (draftRepository.existsByTaskIdAndLowerTitle(taskId, normalized)) {
            return true;
        }
        return knowledgeRepository.existsByCompanyScopeAndLowerTitle(companyId, normalized);
    }

    /** KN-06 预览：按章节列出任务产出的全部草稿（含已采纳/已拒绝） */
    public List<KnowledgeUploadDraft> listDrafts(UUID taskId) {
        return draftRepository.findByTaskIdOrderBySectionNoAscCreatedAtAsc(taskId);
    }

    /** 采纳回执：成功条数 + 被跳过条目与原因（前端逐条展示，不再静默少采纳） */
    public record AdoptResult(int adopted, List<Map<String, Object>> skipped) {
    }

    /**
     * KN-06 批量采纳：只处理本任务下 pending 的草稿；ids 为空表示全部采纳。
     *
     * @return 实际采纳条数与被跳过条目的原因
     */
    @Transactional(rollbackFor = Exception.class)
    public AdoptResult adoptDrafts(UUID taskId, List<UUID> ids, UUID operatorId) {
        KnowledgeUploadTask task = uploadTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "上传任务不存在"));
        List<KnowledgeUploadDraft> pending = draftRepository
                .findByTaskIdAndStatus(taskId, KnowledgeUploadDraft.STATUS_PENDING);
        Set<UUID> wanted = (ids == null || ids.isEmpty()) ? null : new HashSet<>(ids);
        // 书籍模式的来源文档取书名（与萃取时一致），否则取原始文件名
        boolean bookMode = UploadContentMode.isBook(task.getSourceKind());
        String documentName = bookMode ? stripExtension(task.getFileName()) : task.getFileName();
        int adopted = 0;
        List<Map<String, Object>> skippedItems = new java.util.ArrayList<>();
        for (KnowledgeUploadDraft d : pending) {
            if (wanted != null && !wanted.contains(d.getId())) {
                continue;
            }
            // 准入未通过（如版权页残留、正文过短）不允许直接采纳，先补齐再采
            if (d.getQualityIssues() != null && !d.getQualityIssues().isBlank()) {
                log.info("跳过未达准入标准的草稿 draftId={} issues={}", d.getId(), d.getQualityIssues());
                skippedItems.add(Map.of(
                        "draftId", d.getId().toString(),
                        "title", d.getTitle() == null ? "" : d.getTitle(),
                        "reason", "未达准入标准：" + d.getQualityIssues()));
                continue;
            }
            knowledgeRepository.save(KnowledgeEntry.builder()
                    .id(UUID.randomUUID())
                    .companyId(task.getCompanyId())
                    .sourceType(task.getCompanyId() == null
                            ? KnowledgeEntry.SOURCE_TYPE_PLATFORM : KnowledgeEntry.SOURCE_TYPE_TENANT)
                    .category(d.getCategory())
                    .title(d.getTitle())
                    .content(d.getContent())
                    .version(1)
                    .status(KnowledgeEntry.STATUS_PENDING)
                    .origin(bookMode ? KnowledgeEntry.ORIGIN_BOOK : KnowledgeEntry.ORIGIN_INTERNAL)
                    .bookMode(bookMode)
                    .source(d.getSource())
                    .sourceTaskId(task.getId())
                    .sourceDocument(SourceText.truncateDocument(documentName))
                    .createdBy(operatorId)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
            d.setStatus(KnowledgeUploadDraft.STATUS_ADOPTED);
            draftRepository.save(d);
            adopted++;
        }
        log.info("上传草稿批量采纳 taskId={} adopted={} skipped={}",
                taskId, adopted, skippedItems.size());
        return new AdoptResult(adopted, skippedItems);
    }

    /**
     * 详情页「查看原始文档」：按上传任务返回文件名 + 短时效预签名地址。
     * 手工录入/外部导入的条目没有上传任务，由 Controller 返回 404。
     */
    public Map<String, Object> sourceFile(UUID taskId) {
        KnowledgeUploadTask task = uploadTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "来源上传任务不存在"));
        if (task.getFilePath() == null || task.getFilePath().isBlank()) {
            throw new BusinessException(404, "原始文档已不可用");
        }
        return Map.of(
                "fileName", task.getFileName() == null ? "" : task.getFileName(),
                "url", fileStorageService.presignedUrl(task.getFilePath(), 300));
    }

    /**
     * 人工补齐草稿：未达准入标准的条目允许先改标题/正文/分类/出处，保存后重算质量分。
     * 只允许改本篇草稿，不允许借机改任务归属。
     */
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeUploadDraft updateDraft(UUID taskId, UUID draftId, String title, String content,
                                            String category, String source) {
        KnowledgeUploadDraft d = draftRepository.findById(draftId)
                .orElseThrow(() -> new BusinessException(404, "草稿不存在"));
        if (!taskId.equals(d.getTaskId())) {
            throw new BusinessException(400, "草稿不属于该任务");
        }
        if (!KnowledgeUploadDraft.STATUS_PENDING.equals(d.getStatus())) {
            throw new BusinessException(400, "已采纳/已拒绝的草稿不能修改");
        }
        if (!KnowledgeEntry.isValidCategory(category)) {
            throw new BusinessException(400, "分类不合法");
        }
        boolean bookMode = uploadTaskRepository.findById(taskId)
                .map(t -> UploadContentMode.isBook(t.getSourceKind()))
                .orElse(false);
        String newSource = source == null || source.isBlank() ? d.getSource() : source.trim();
        ContentQualityGate.QualityReport report = ContentQualityGate.checkKnowledge(
                title, content, category, newSource, bookMode);
        d.setTitle(title == null ? null : title.trim());
        d.setContent(content == null ? null : content.trim());
        d.setCategory(category);
        d.setSource(newSource);
        d.setQualityScore(report.score());
        d.setQualityIssues(report.issues().isEmpty() ? null : report.issueText());
        d.setQualityWarnings(report.warnings().isEmpty() ? null : report.warningText());
        draftRepository.save(d);
        return d;
    }

    /** KN-06 批量拒绝：ids 为空表示拒绝全部 pending 草稿。 */
    @Transactional(rollbackFor = Exception.class)
    public int rejectDrafts(UUID taskId, List<UUID> ids) {
        List<KnowledgeUploadDraft> pending = draftRepository
                .findByTaskIdAndStatus(taskId, KnowledgeUploadDraft.STATUS_PENDING);
        Set<UUID> wanted = (ids == null || ids.isEmpty()) ? null : new HashSet<>(ids);
        int rejected = 0;
        for (KnowledgeUploadDraft d : pending) {
            if (wanted != null && !wanted.contains(d.getId())) {
                continue;
            }
            d.setStatus(KnowledgeUploadDraft.STATUS_REJECTED);
            draftRepository.save(d);
            rejected++;
        }
        log.info("上传草稿批量拒绝 taskId={} rejected={}", taskId, rejected);
        return rejected;
    }

    /** 自动识别为书籍时回写素材类型，页面据此展示「书籍模式」 */
    @Transactional(rollbackFor = Exception.class)
    public void setTaskSourceKind(UUID taskId, String sourceKind) {
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            t.setSourceKind(UploadContentMode.normalize(sourceKind));
            uploadTaskRepository.save(t);
        });
    }

    /** 写入任务处理说明（书籍模式提示 / 跳过原因） */
    @Transactional(rollbackFor = Exception.class)
    public void setTaskGuide(UUID taskId, String message) {
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            t.setGuideMessage(appendGuide(t.getGuideMessage(), message));
            uploadTaskRepository.save(t);
        });
    }

    /** 写入章节总数（结构解析后） */
    @Transactional(rollbackFor = Exception.class)
    public void setTaskTotal(UUID taskId, int total) {
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            t.setTotalSections(total);
            uploadTaskRepository.save(t);
        });
    }

    /** 标记任务完成；若全文档未产出任何候选则记为失败并写原因 */
    @Transactional(rollbackFor = Exception.class)
    public void markTaskDone(UUID taskId) {
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            boolean empty = t.getProducedCount() == null || t.getProducedCount() == 0;
            t.setStatus(empty ? KnowledgeUploadTask.STATUS_FAILED : KnowledgeUploadTask.STATUS_DONE);
            if (empty) {
                t.setErrorMessage("文档未提取到有效知识条目：可能全是版权页/目录/叙事段落，或内容与已有知识重复");
            }
            uploadTaskRepository.save(t);
        });
    }

    /** 标记任务失败（记录失败原因，供前端展示） */
    @Transactional(rollbackFor = Exception.class)
    public void markTaskFailed(UUID taskId, String reason) {
        log.warn("知识上传任务失败 taskId={} reason={}", taskId, reason);
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            t.setStatus(KnowledgeUploadTask.STATUS_FAILED);
            t.setErrorMessage(truncateReason(reason));
            uploadTaskRepository.save(t);
        });
    }

    // ==================== 上传 / 续传（Controller → 本层） ====================

    /**
     * 上传文档触发异步萃取：存文件 → 建 task(extracting，含文件路径) → 触发 @Async，立即返回 taskId。
     * 无 @Transactional（文件 IO 与 AI 触发在事务外），task 落库走 self 短事务。
     */
    public UUID uploadKnowledge(MultipartFile file, UUID companyId, UUID operatorId, String sourceKind) {
        // companyId 为 null 表示 super_admin 上传公共知识
        String fileName = file.getOriginalFilename();
        String filePath = fileStorageService.saveFile(file, "knowledge");
        UUID taskId = self.createUploadTask(companyId, operatorId, fileName, filePath,
                UploadContentMode.normalize(sourceKind));
        self.extractUploadAsync(taskId, companyId, operatorId, filePath, fileName);
        return taskId;
    }

    /** 兼容旧调用（未指定素材类型） */
    public UUID uploadKnowledge(MultipartFile file, UUID companyId, UUID operatorId) {
        return uploadKnowledge(file, companyId, operatorId, UploadContentMode.INTERNAL);
    }

    /** 续传上传任务：从 processedSections 检查点继续，不重做已完成章节。已完成的任务拒绝续传。 */
    public UUID retryUpload(UUID taskId) {
        KnowledgeUploadTask task = uploadTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "上传任务不存在"));
        if (KnowledgeUploadTask.STATUS_DONE.equals(task.getStatus())) {
            throw new BusinessException(400, "任务已完成，无需重试");
        }
        // 用进程内集合判断「本 JVM 是否仍在萃取」：崩溃遗留的孤儿任务 status 停在 extracting，
        // 但不在集合里，应放行续传（不能看 status）
        if (extractingTasks.contains(taskId)) {
            throw new BusinessException(400, "任务正在萃取中，请稍候");
        }
        if (task.getFilePath() == null || task.getFilePath().isBlank()) {
            throw new BusinessException(400, "任务缺少文件路径，无法续传");
        }
        self.resetTaskForRetry(taskId);
        self.extractUploadAsync(taskId, task.getCompanyId(), task.getCreatedBy(),
                task.getFilePath(), task.getFileName());
        return taskId;
    }

    /** 建上传任务（短事务，提交后异步线程才能读到） */
    @Transactional(rollbackFor = Exception.class)
    public UUID createUploadTask(UUID companyId, UUID operatorId, String fileName, String filePath,
                                 String sourceKind) {
        KnowledgeUploadTask task = KnowledgeUploadTask.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .fileName(fileName)
                .filePath(filePath)
                .status(KnowledgeUploadTask.STATUS_EXTRACTING)
                .sourceKind(UploadContentMode.normalize(sourceKind))
                .producedCount(0)
                .skippedCount(0)
                .totalSections(0)
                .processedSections(0)
                .createdBy(operatorId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        uploadTaskRepository.save(task);
        return task.getId();
    }

    /** 续传前置：状态重置为 extracting、清空失败原因（processedSections 保留，作为续传起点） */
    @Transactional(rollbackFor = Exception.class)
    public void resetTaskForRetry(UUID taskId) {
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            t.setStatus(KnowledgeUploadTask.STATUS_EXTRACTING);
            t.setErrorMessage(null);
            uploadTaskRepository.save(t);
        });
    }

    // ==================== 私有辅助 ====================

    private List<KnowledgeCandidate> extractChunk(String chunk, String chain, String domain,
                                                  boolean bookMode, String bookTitle) throws Exception {
        String section = chain == null ? "" : chain;
        // 书籍属于对外资料：送模型前做强脱敏（公司名/项目名/金额/政府机关）
        if (bookMode) {
            chunk = PiiMasker.maskStrict(chunk);
        }
        String template = bookMode ? "material_knowledge_book.md" : "material_knowledge.md";
        Map<String, String> vars = bookMode
                ? Map.of("text", chunk, "section", section, "book", bookTitle == null ? "" : bookTitle)
                : Map.of("text", chunk, "section", section);
        String prompt = (domain == null || domain.isBlank())
                ? promptLoader.format(template, vars)
                : promptLoader.format(template, vars, domain);
        String json = chatStreamAdapter.chat(prompt);
        if (json == null) {
            return List.of();
        }
        String clean = json.trim();
        if (clean.startsWith("```")) {
            clean = clean.replaceAll("```json\\s*|```\\s*", "").trim();
        }
        List<Map<String, Object>> list = objectMapper.readValue(clean,
                new TypeReference<List<Map<String, Object>>>() {
                });
        List<KnowledgeCandidate> result = new ArrayList<>();
        for (Map<String, Object> m : list) {
            String title = asText(m.get("title"));
            String category = asText(m.get("category"));
            String content = asText(m.get("content"));
            if (title == null || title.isBlank() || content == null || content.isBlank()) {
                continue;
            }
            String anchor = bookMode ? asText(m.get("anchor")) : null;
            String source = section;
            if (anchor != null && !anchor.isBlank() && !section.contains(anchor)) {
                source = section.isBlank() ? anchor : section + " / " + anchor;
            }
            result.add(new KnowledgeCandidate(title, category, content, source));
        }
        return result;
    }

    /** 模型字段可能是字符串/对象；统一转文本，避免强转失败把整块候选丢掉 */
    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** 按「标题 + 章节」去重，保留首次出现（同名不同章节不合并，避免误删） */
    private List<KnowledgeCandidate> deduplicate(List<KnowledgeCandidate> candidates) {
        Set<String> seen = new HashSet<>();
        List<KnowledgeCandidate> result = new ArrayList<>();
        for (KnowledgeCandidate c : candidates) {
            String key = (c.title() == null ? "" : c.title().trim().toLowerCase())
                    + "|" + (c.source() == null ? "" : c.source().trim());
            if (seen.add(key)) {
                result.add(c);
            }
        }
        return result;
    }

    /** 追加处理说明（去重后拼接，避免覆盖书籍模式提示） */
    private String appendGuide(String current, String add) {
        if (add == null || add.isBlank()) {
            return current;
        }
        if (current == null || current.isBlank()) {
            return add;
        }
        if (current.contains(add)) {
            return current;
        }
        return current + " " + add;
    }

    /** 文件名去扩展名，作为书名 */
    private String stripExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** 失败原因截断到列上限，避免超长写入失败 */
    private String truncateReason(String reason) {
        if (reason == null) {
            return "未知错误";
        }
        return reason.length() > 1000 ? reason.substring(0, 1000) : reason;
    }

    /** 知识候选 */
    public record KnowledgeCandidate(String title, String category, String content, String source) {
    }
}
