package com.aiextract.service;

import com.aiextract.common.AiErrorMapper;
import com.aiextract.common.SourceText;
import com.aiextract.config.PromptLoader;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.CaseUploadTask;
import com.aiextract.model.IndustryCase;
import com.aiextract.repository.CaseUploadTaskRepository;
import com.aiextract.repository.IndustryCaseRepository;
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
 * 案例管道清洗服务 —— 从「书 / 已整理案例文档」提取结构化案例（独立管道，不复用经验管道）。
 *
 * <p>与知识管道 {@link KnowledgeCleaningService} 一样，只复用通用文档格式清洗
 * {@code MaterialNoiseCleaner.cleanFormatNoise(text,"document")}、通用文件解析
 * {@link FileParseService} 与结构解析分块，不复用 {@code tagContext}、不做独立情境标注。</p>
 *
 * <p>章节级进度 + 断点续传（T16）：与知识管道对称，按「节」逐节提交检查点，中断后
 * {@link #retryCase(UUID)} 续传。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseCleaningService {

    /** 自注入代理：AI 调用在事务外，落库走 self 短事务 */
    @Autowired
    @Lazy
    private CaseCleaningService self;

    private final IndustryCaseRepository caseRepository;
    private final CaseUploadTaskRepository uploadTaskRepository;
    private final MaterialNoiseCleaner noiseCleaner;
    private final ChatStreamAdapter chatStreamAdapter;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final FileParseService fileParseService;
    private final FileStorageService fileStorageService;

    /** 正在萃取的任务集合（进程内）：区分「本 JVM 仍在跑」与「上次崩溃遗留的孤儿任务」，同知识管道。 */
    private final Set<UUID> extractingTasks = ConcurrentHashMap.newKeySet();

    /** 书籍模式单次上传最多产出的案例数：书里的案例是二手资料，不鼓励整本灌入 */
    public static final int MAX_BOOK_CASES = 100;

    /** 解析后正文上限（默认 30 万字），超出直接拒绝并提示拆分 */
    @Value("${app.case.max-text-chars:300000}")
    private int maxTextChars;

    /**
     * 从文档全文提取案例候选：格式清洗 → 轻量归一化 → 结构解析按节分块 → 分批 AI 提取 → 按标题去重。
     * 每个候选的 source 记「章·节」标题链。无 @Transactional（AI 调用在事务外）。
     */
    public List<CaseCandidate> extractFromDocument(String text, String domain) {
        return extractFromDocument(text, domain, UploadContentMode.INTERNAL, null);
    }

    /** 同上，可指定素材类型：书籍走二手案例提示词。 */
    public List<CaseCandidate> extractFromDocument(String text, String domain,
                                                   String sourceKind, String bookTitle) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String cleaned = noiseCleaner.cleanFormatNoise(text, "document");
        String normalized = PiiMasker.mask(KnowledgeCleaningService.normalizeLight(cleaned));
        List<DocumentStructureParser.Section> sections = DocumentStructureParser.parseSections(normalized);
        boolean bookMode = UploadContentMode.isBook(sourceKind);
        List<CaseCandidate> all = new ArrayList<>();
        for (DocumentStructureParser.Section section : sections) {
            all.addAll(extractCaseSection(section, domain, bookMode, bookTitle).candidates());
        }
        return deduplicate(all);
    }

    /** 异步上传萃取（可断点续传）：解析文件 → 按节逐节提取并提交检查点 → 完成后标记 done。 */
    @Async("cleanExecutor")
    public void extractCaseUploadAsync(UUID taskId, UUID companyId, UUID operatorId,
                                       String filePath, String fileName) {
        if (!extractingTasks.add(taskId)) {
            log.warn("案例上传任务已在萃取中，跳过重复触发 taskId={}", taskId);
            return;
        }
        try {
            int resumeFrom = uploadTaskRepository.findById(taskId)
                    .map(t -> t.getProcessedSections() == null ? 0 : t.getProcessedSections())
                    .orElse(0);
            String text = fileParseService.parseToText(filePath, fileName);
            if (text == null || text.isBlank()) {
                self.markCaseTaskFailed(taskId, "文件解析失败或内容为空");
                return;
            }
            if (text.length() > maxTextChars) {
                self.markCaseTaskFailed(taskId, "文档解析后 " + text.length() + " 字，超过单次上限 "
                        + maxTextChars + " 字。案例信息密度低，建议只上传与业务最相关的章节；单文件最大 50MB。");
                return;
            }
            String cleaned = noiseCleaner.cleanFormatNoise(text, "document");
            // 进入模型与落库前统一脱敏：手机号/身份证/银行卡/座机/邮箱
            String normalized = PiiMasker.mask(KnowledgeCleaningService.normalizeLight(cleaned));
            List<DocumentStructureParser.Section> sections = DocumentStructureParser.parseSections(normalized);
            if (sections.isEmpty()) {
                self.markCaseTaskFailed(taskId, "文档未提取到有效案例");
                return;
            }
            String declared = uploadTaskRepository.findById(taskId)
                    .map(CaseUploadTask::getSourceKind).orElse(UploadContentMode.INTERNAL);
            boolean bookMode = UploadContentMode.isBook(declared)
                    || UploadContentMode.detectBookLike(fileName, normalized, sections.size());
            if (bookMode && !UploadContentMode.isBook(declared)) {
                self.setCaseTaskSourceKind(taskId, UploadContentMode.BOOK);
                self.setCaseTaskGuide(taskId,
                        "检测到书籍/课程版式，已按「二手案例」模式提取：只保留信息完整的客户故事，"
                                + "结果不明的片段会被丢弃。");
            }
            String bookTitle = bookMode ? stripExtension(fileName) : null;
            self.setCaseTaskTotal(taskId, sections.size());
            int resume = Math.min(resumeFrom, sections.size());
            for (int i = resume; i < sections.size(); i++) {
                SectionResult result = extractCaseSection(sections.get(i), null, bookMode, bookTitle);
                self.commitCaseSection(taskId, companyId, operatorId, result.candidates(),
                        i + 1, result.failed() ? 1 : 0, bookMode, bookTitle);
            }
            self.markCaseTaskDone(taskId);
        } catch (Exception e) {
            log.error("案例上传萃取失败 taskId={}: {}", taskId, e.getMessage(), e);
            self.markCaseTaskFailed(taskId, AiErrorMapper.describe(e));
        } finally {
            extractingTasks.remove(taskId);
        }
    }

    // ==================== 落库 / 进度（经 self 代理，短事务） ====================

    /** 单节提取结果：候选 + 是否发生过 chunk 级失败（任务内提示部分章节未提取） */
    private record SectionResult(List<CaseCandidate> candidates, boolean failed) {
    }

    /** 提取单个节的案例候选（该节所有 chunk 提取后去重），是断点续传的最小提交单元。 */
    private SectionResult extractCaseSection(DocumentStructureParser.Section section, String domain,
                                             boolean bookMode, String bookTitle) {
        List<CaseCandidate> all = new ArrayList<>();
        boolean failed = false;
        for (String chunk : DocumentStructureParser.splitSection(section.content())) {
            try {
                all.addAll(extractChunk(chunk, section.chain(), domain, bookMode, bookTitle));
            } catch (Exception e) {
                // 单块失败不再静默：章节计入失败数，任务继续处理后续章节
                failed = true;
                log.warn("案例块提取失败（章节将计入失败数）: {}", e.getMessage());
            }
        }
        return new SectionResult(deduplicate(all), failed);
    }

    /** 提交一个节的案例候选并推进检查点（原子）：落库候选 + 计数累加 + processedSections 前移。 */
    @Transactional(rollbackFor = Exception.class)
    public int commitCaseSection(UUID taskId, UUID companyId, UUID operatorId,
                                 List<CaseCandidate> candidates, int processedSections,
                                 int failedDelta, boolean bookMode, String bookTitle) {
        CaseUploadTask task = uploadTaskRepository.findById(taskId).orElse(null);
        int existing = task == null || task.getProducedCount() == null ? 0 : task.getProducedCount();
        int count = 0;
        int skipped = 0;
        boolean capHit = false;
        for (CaseCandidate candidate : candidates) {
            // 模型输出兜底脱敏：书籍对外使用强脱敏，内部资料用标准脱敏
            java.util.function.Function<String, String> masker =
                    bookMode ? PiiMasker::maskStrict : PiiMasker::mask;
            CaseCandidate c = new CaseCandidate(
                    masker.apply(candidate.title()),
                    masker.apply(candidate.customerProfile()),
                    candidate.funnelStage(),
                    candidate.sceneTag(),
                    masker.apply(candidate.objection()),
                    masker.apply(candidate.emotionChange()),
                    masker.apply(candidate.keyActions()),
                    masker.apply(candidate.scripts()),
                    masker.apply(candidate.dialogueQuote()),
                    masker.apply(candidate.advanceNodes()),
                    candidate.result(),
                    masker.apply(candidate.reusablePoints()),
                    masker.apply(candidate.failurePoints()),
                    masker.apply(candidate.source()));
            if (!IndustryCase.isValidResult(c.result())) {
                log.warn("跳过非法结果案例候选 title={} result={}", c.title(), c.result());
                skipped++;
                continue;
            }
            if (bookMode && existing + count >= MAX_BOOK_CASES) {
                capHit = true;
                skipped++;
                continue;
            }
            if (isDuplicateCase(companyId, c.title())) {
                skipped++;
                continue;
            }
            // 来源统一带文档名/书名，只有章节链无法追溯原始文档
            String documentName = bookMode && bookTitle != null && !bookTitle.isBlank()
                    ? bookTitle
                    : (task == null ? null : task.getFileName());
            String source = SourceText.compose(documentName, c.source());
            ContentQualityGate.QualityReport report = ContentQualityGate.checkCase(
                    c.title(), c.result(), c.objection(), c.customerProfile(),
                    c.keyActions(), c.scripts(), c.dialogueQuote(), source, bookMode);
            // 书籍案例是二手资料，一律先落草稿；未达标的也落草稿并由页面提示补齐
            String status = (bookMode || !report.pass())
                    ? IndustryCase.STATUS_DRAFT : IndustryCase.STATUS_PENDING;
            IndustryCase entry = IndustryCase.builder()
                    .id(UUID.randomUUID())
                    .companyId(companyId)
                    .title(c.title())
                    // JSONB 列不接受纯文本：模型偶尔返回普通字符串，包装成 {"text": ...} 而不是丢掉整条候选
                    .customerProfile(normalizeJsonObject(c.customerProfile()))
                    .funnelStage(c.funnelStage())
                    .sceneTag(c.sceneTag())
                    .objection(c.objection())
                    .emotionChange(c.emotionChange())
                    .keyActions(c.keyActions())
                    .scripts(c.scripts())
                    .dialogueQuote(normalizeJsonArray(c.dialogueQuote()))
                    .advanceNodes(c.advanceNodes())
                    .result(c.result())
                    .reusablePoints(c.reusablePoints())
                    .failurePoints(c.failurePoints())
                    .source(source)
                    .sourceTaskId(taskId)
                    .sourceDocument(SourceText.truncateDocument(documentName))
                    .origin(bookMode ? IndustryCase.ORIGIN_BOOK : IndustryCase.ORIGIN_INTERNAL)
                    .qualityIssues(report.issues().isEmpty() ? null : report.issueText())
                    .qualityWarnings(report.warnings().isEmpty() ? null : report.warningText())
                    .contributorId(operatorId)
                    .version(1)
                    .status(status)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            caseRepository.save(entry);
            count++;
        }
        final int added = count;
        final int skippedDelta = skipped;
        final boolean cap = capHit;
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            t.setProducedCount(t.getProducedCount() + added);
            t.setSkippedCount((t.getSkippedCount() == null ? 0 : t.getSkippedCount()) + skippedDelta);
            if (failedDelta > 0) {
                int failed = t.getFailedSections() == null ? 0 : t.getFailedSections();
                t.setFailedSections(failed + failedDelta);
                t.setGuideMessage(appendGuide(t.getGuideMessage(),
                        "有部分章节模型提取失败，未能产出案例；可稍后在任务详情重试上传或手动补录。"));
            }
            t.setProcessedSections(processedSections);
            if (cap) {
                t.setGuideMessage(appendGuide(t.getGuideMessage(),
                        "单次上传最多产出 " + MAX_BOOK_CASES + " 条案例，超出部分已跳过；建议只提取与业务最相关的章节。"));
            }
            uploadTaskRepository.save(t);
        });
        return count;
    }

    /**
     * 详情页「查看原始文档」：按上传任务返回文件名 + 短时效预签名地址。
     * 手工录入/外部导入的条目没有上传任务，由 Controller 返回 404。
     */
    public Map<String, Object> sourceFile(UUID taskId) {
        CaseUploadTask task = uploadTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "来源上传任务不存在"));
        if (task.getFilePath() == null || task.getFilePath().isBlank()) {
            throw new BusinessException(404, "原始文档已不可用");
        }
        return Map.of(
                "fileName", task.getFileName() == null ? "" : task.getFileName(),
                "url", fileStorageService.presignedUrl(task.getFilePath(), 300));
    }

    /** 同企业标题查重（书籍与已有案例重复时不再入库） */
    private boolean isDuplicateCase(UUID companyId, String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        return caseRepository.existsByCompanyScopeAndLowerTitle(companyId, title.trim().toLowerCase());
    }

    /** 自动识别为书籍时回写素材类型 */
    @Transactional(rollbackFor = Exception.class)
    public void setCaseTaskSourceKind(UUID taskId, String sourceKind) {
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            t.setSourceKind(UploadContentMode.normalize(sourceKind));
            uploadTaskRepository.save(t);
        });
    }

    /** 写入任务处理说明（书籍模式提示 / 跳过原因） */
    @Transactional(rollbackFor = Exception.class)
    public void setCaseTaskGuide(UUID taskId, String message) {
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            t.setGuideMessage(appendGuide(t.getGuideMessage(), message));
            uploadTaskRepository.save(t);
        });
    }

    /** 写入章节总数（结构解析后） */
    @Transactional(rollbackFor = Exception.class)
    public void setCaseTaskTotal(UUID taskId, int total) {
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            t.setTotalSections(total);
            uploadTaskRepository.save(t);
        });
    }

    /** 标记任务完成；若全文档未产出任何候选则记为失败并写原因 */
    @Transactional(rollbackFor = Exception.class)
    public void markCaseTaskDone(UUID taskId) {
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            boolean empty = t.getProducedCount() == null || t.getProducedCount() == 0;
            t.setStatus(empty ? CaseUploadTask.STATUS_FAILED : CaseUploadTask.STATUS_DONE);
            if (empty) {
                t.setErrorMessage("文档未提取到有效案例：可能没有结果明确的客户故事，或与已有案例重复");
            }
            uploadTaskRepository.save(t);
        });
    }

    /** 标记任务失败（记录失败原因，供前端展示） */
    @Transactional(rollbackFor = Exception.class)
    public void markCaseTaskFailed(UUID taskId, String reason) {
        log.warn("案例上传任务失败 taskId={} reason={}", taskId, reason);
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            t.setStatus(CaseUploadTask.STATUS_FAILED);
            t.setErrorMessage(truncateReason(reason));
            uploadTaskRepository.save(t);
        });
    }

    // ==================== 上传 / 续传（Controller → 本层） ====================

    /** 上传文档触发异步萃取案例：存文件 → 建 task(extracting，含文件路径) → 触发 @Async，立即返回 taskId。 */
    public UUID uploadCase(MultipartFile file, UUID companyId, UUID operatorId, String sourceKind) {
        // companyId 为 null 表示 super_admin 上传公共案例
        String fileName = file.getOriginalFilename();
        String filePath = fileStorageService.saveFile(file, "case");
        UUID taskId = self.createCaseUploadTask(companyId, operatorId, fileName, filePath,
                UploadContentMode.normalize(sourceKind));
        self.extractCaseUploadAsync(taskId, companyId, operatorId, filePath, fileName);
        return taskId;
    }

    /** 兼容旧调用（未指定素材类型） */
    public UUID uploadCase(MultipartFile file, UUID companyId, UUID operatorId) {
        return uploadCase(file, companyId, operatorId, UploadContentMode.INTERNAL);
    }

    /** 续传上传任务：从 processedSections 检查点继续。已完成的任务拒绝续传。 */
    public UUID retryCase(UUID taskId) {
        CaseUploadTask task = uploadTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(404, "上传任务不存在"));
        if (CaseUploadTask.STATUS_DONE.equals(task.getStatus())) {
            throw new BusinessException(400, "任务已完成，无需重试");
        }
        // 用进程内集合判断「本 JVM 是否仍在萃取」，崩溃遗留的孤儿任务应放行续传
        if (extractingTasks.contains(taskId)) {
            throw new BusinessException(400, "任务正在萃取中，请稍候");
        }
        if (task.getFilePath() == null || task.getFilePath().isBlank()) {
            throw new BusinessException(400, "任务缺少文件路径，无法续传");
        }
        self.resetCaseTaskForRetry(taskId);
        self.extractCaseUploadAsync(taskId, task.getCompanyId(), task.getCreatedBy(),
                task.getFilePath(), task.getFileName());
        return taskId;
    }

    /** 建案例上传任务（短事务，提交后异步线程才能读到） */
    @Transactional(rollbackFor = Exception.class)
    public UUID createCaseUploadTask(UUID companyId, UUID operatorId, String fileName, String filePath,
                                     String sourceKind) {
        CaseUploadTask task = CaseUploadTask.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .fileName(fileName)
                .filePath(filePath)
                .status(CaseUploadTask.STATUS_EXTRACTING)
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
    public void resetCaseTaskForRetry(UUID taskId) {
        uploadTaskRepository.findById(taskId).ifPresent(t -> {
            t.setStatus(CaseUploadTask.STATUS_EXTRACTING);
            t.setErrorMessage(null);
            uploadTaskRepository.save(t);
        });
    }

    // ==================== 私有辅助 ====================

    private List<CaseCandidate> extractChunk(String chunk, String chain, String domain,
                                             boolean bookMode, String bookTitle) {
        try {
            String section = chain == null ? "" : chain;
            // 书籍属于对外资料：送模型前做强脱敏（公司名/项目名/金额/政府机关）
            if (bookMode) {
                chunk = PiiMasker.maskStrict(chunk);
            }
            String template = bookMode ? "material_case_book.md" : "material_case.md";
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
            List<CaseCandidate> result = new ArrayList<>();
            for (Map<String, Object> m : list) {
                String title = asText(m.get("title"));
                if (title == null || title.isBlank()) {
                    continue;
                }
                String anchor = bookMode ? asText(m.get("anchor")) : null;
                String source = section;
                if (anchor != null && !anchor.isBlank() && !section.contains(anchor)) {
                    source = section.isBlank() ? anchor : section + " / " + anchor;
                }
                result.add(new CaseCandidate(
                        title,
                        asText(m.get("customerProfile")),
                        asText(m.get("funnelStage")),
                        asText(m.get("sceneTag")),
                        asText(m.get("objection")),
                        asText(m.get("emotionChange")),
                        asText(m.get("keyActions")),
                        asText(m.get("scripts")),
                        asText(m.get("dialogueQuote")),
                        asText(m.get("advanceNodes")),
                        asText(m.get("result")),
                        asText(m.get("reusablePoints")),
                        asText(m.get("failurePoints")),
                        source));
            }
            return result;
        } catch (Exception e) {
            log.warn("案例块提取失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 模型可能返回字符串/数组/对象；统一转成文本，避免一次强转失败丢掉整块候选 */
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

    /**
     * JSONB 列兜底：合法 JSON 原样保留；普通文本包装成 {"text": "..."}；
     * 模型返回对象/数组时序列化。宁可降级也不丢候选。
     */
    /** JSONB 数组兜底：数组原样保留，单句/普通文本包装成单元素数组 */
    private String normalizeJsonArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            if (objectMapper.readTree(value).isArray()) {
                return value;
            }
        } catch (Exception ignored) {
            // 非 JSON，按单条原话处理
        }
        try {
            return objectMapper.writeValueAsString(List.of(value));
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            objectMapper.readTree(value);
            return value;
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(Map.of("text", value));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private List<CaseCandidate> deduplicate(List<CaseCandidate> candidates) {
        Set<String> seen = new HashSet<>();
        List<CaseCandidate> result = new ArrayList<>();
        for (CaseCandidate c : candidates) {
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

    /** 案例候选 */
    public record CaseCandidate(
            String title, String customerProfile, String funnelStage, String sceneTag,
            String objection, String emotionChange, String keyActions, String scripts,
            String dialogueQuote, String advanceNodes, String result, String reusablePoints,
            String failurePoints, String source) {
    }
}
