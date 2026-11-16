package com.aiextract.service;

import com.aiextract.common.SourceText;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AdminAuditLog;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.AdminAuditLogRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 外部预提取内容导入 —— 用户在自己的大模型里按提示词产出 JSON，再批量导入。
 *
 * <p>为什么要这条通道：整本书/长文档在平台内萃取要跑很多次模型，用户也可能已经有自己
 * 顺手的模型。与其让他们手工一条条录，不如提供受控的批量导入：结构与站内萃取完全一致，
 * 统一过质量门禁、查重、脱敏，不达标条目给出具体原因。</p>
 *
 * <p>导入不绕过审核：案例书稿/不达标进草稿，知识进待审核；都要人工审批后才生效。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentImportService {

    /** 单次导入上限：再多请分批，避免一次请求长时间占用连接与审核队列 */
    public static final int MAX_ITEMS = 200;

    private final IndustryCaseRepository caseRepository;
    private final KnowledgeEntryRepository knowledgeRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /** 单条目导入结果 */
    private record ItemResult(int index, String title, String result, String reason) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", index);
            m.put("title", title);
            m.put("result", result);
            if (reason != null) {
                m.put("reason", reason);
            }
            return m;
        }
    }

    // ==================== 案例导入 ====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importCases(List<Map<String, Object>> rawItems, boolean dryRun,
                                           String sourceKind, String sourceName,
                                           UUID companyId, UUID operatorId) {
        assertItems(rawItems);
        boolean book = UploadContentMode.isBook(sourceKind);
        Function<String, String> masker = (book || companyId == null) ? PiiMasker::maskStrict : PiiMasker::mask;
        Set<String> seenTitles = new HashSet<>();
        List<ItemResult> results = new ArrayList<>();
        int imported = 0;
        int needsSupplement = 0;
        int skipped = 0;
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < rawItems.size(); i++) {
            Map<String, Object> raw = rawItems.get(i);
            String title = masker.apply(text(raw, "title"));
            if (title == null || title.isBlank()) {
                results.add(new ItemResult(i, null, "skipped", "标题为空"));
                skipped++;
                continue;
            }
            if (!seenTitles.add(title.trim().toLowerCase())) {
                results.add(new ItemResult(i, title, "skipped", "本次导入内标题重复"));
                skipped++;
                continue;
            }
            if (caseRepository.existsByCompanyScopeAndLowerTitle(companyId, title.trim().toLowerCase())) {
                results.add(new ItemResult(i, title, "skipped", "标题与已有案例重复"));
                skipped++;
                continue;
            }

            // 列宽保护：超长单条只跳过该条并说明原因，不让整批回滚
            if (title.trim().length() > 255) {
                results.add(new ItemResult(i, title, "skipped", "标题超过 255 字，请精简后重试"));
                skipped++;
                continue;
            }
            String customerProfile = normalizeJsonObject(masker.apply(jsonText(raw.get("customerProfile"))));
            String dialogueQuote = normalizeJsonArray(masker.apply(jsonText(raw.get("dialogueQuote"))));
            String funnelStage = cap(text(raw, "funnelStage"), 20);
            String sceneTag = cap(text(raw, "sceneTag"), 50);
            String objection = masker.apply(text(raw, "objection"));
            String emotionChange = masker.apply(text(raw, "emotionChange"));
            String keyActions = masker.apply(text(raw, "keyActions"));
            String scripts = masker.apply(text(raw, "scripts"));
            String advanceNodes = masker.apply(text(raw, "advanceNodes"));
            String result = text(raw, "result");
            String reusablePoints = masker.apply(text(raw, "reusablePoints"));
            String failurePoints = masker.apply(text(raw, "failurePoints"));
            String itemSource = masker.apply(text(raw, "source"));
            String source = composeSource(sourceName, itemSource);

            ContentQualityGate.QualityReport report = ContentQualityGate.checkCase(
                    title, result, objection, customerProfile, keyActions, scripts,
                    dialogueQuote, source, book);
            boolean supplement = book || !report.pass();
            if (!IndustryCase.isValidResult(result)) {
                results.add(new ItemResult(i, title, "skipped", "结果必须是 赢单/丢单/搁置（win/loss/delay）"));
                skipped++;
                continue;
            }

            if (!dryRun) {
                IndustryCase entry = IndustryCase.builder()
                        .id(UUID.randomUUID())
                        .companyId(companyId)
                        .title(title.trim())
                        .customerProfile(customerProfile)
                        .funnelStage(funnelStage)
                        .sceneTag(sceneTag)
                        .dialogueQuote(dialogueQuote)
                        .objection(objection)
                        .emotionChange(emotionChange)
                        .keyActions(keyActions)
                        .scripts(scripts)
                        .advanceNodes(advanceNodes)
                        .result(result)
                        .reusablePoints(reusablePoints)
                        .failurePoints(failurePoints)
                        .source(source)
                        .sourceDocument(documentName(sourceName))
                        .origin(book ? IndustryCase.ORIGIN_BOOK : IndustryCase.ORIGIN_INTERNAL)
                        .qualityIssues(report.issues().isEmpty() ? null : report.issueText())
                        .qualityWarnings(report.warnings().isEmpty() ? null : report.warningText())
                        .contributorId(operatorId)
                        .version(1)
                        .status(supplement ? IndustryCase.STATUS_DRAFT : IndustryCase.STATUS_PENDING)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                caseRepository.save(entry);
                writeAudit("import_case", "case", entry.getId(), Map.of("title", title, "book", book));
            }

            if (supplement) {
                needsSupplement++;
                String reason = report.issues().isEmpty()
                        ? "书籍案例需补齐字段后审批"
                        : "未达准入标准：" + report.issueText();
                results.add(new ItemResult(i, title, "needs_supplement", reason));
            } else {
                imported++;
                results.add(new ItemResult(i, title, "imported", null));
            }
        }

        return resultMap(dryRun, results, imported, needsSupplement, skipped);
    }

    // ==================== 知识导入 ====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importKnowledge(List<Map<String, Object>> rawItems, boolean dryRun,
                                               String sourceKind, String sourceName,
                                               UUID companyId, UUID operatorId) {
        assertItems(rawItems);
        boolean book = UploadContentMode.isBook(sourceKind);
        Function<String, String> masker = (book || companyId == null) ? PiiMasker::maskStrict : PiiMasker::mask;
        Set<String> seenTitles = new HashSet<>();
        List<ItemResult> results = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < rawItems.size(); i++) {
            Map<String, Object> raw = rawItems.get(i);
            String title = masker.apply(text(raw, "title"));
            String category = text(raw, "category");
            String content = masker.apply(text(raw, "content"));
            String itemSource = masker.apply(text(raw, "source"));
            String source = composeSource(sourceName, itemSource);

            if (title == null || title.isBlank()) {
                results.add(new ItemResult(i, null, "skipped", "标题为空"));
                skipped++;
                continue;
            }
            if (title.trim().length() > 255) {
                results.add(new ItemResult(i, title, "skipped", "标题超过 255 字，请精简后重试"));
                skipped++;
                continue;
            }
            if (!seenTitles.add(title.trim().toLowerCase())) {
                results.add(new ItemResult(i, title, "skipped", "本次导入内标题重复"));
                skipped++;
                continue;
            }
            if (knowledgeRepository.existsByCompanyScopeAndLowerTitle(companyId, title.trim().toLowerCase())) {
                results.add(new ItemResult(i, title, "skipped", "标题与已有知识重复"));
                skipped++;
                continue;
            }

            ContentQualityGate.QualityReport report = ContentQualityGate.checkKnowledge(
                    title, content, category, source, book);
            if (!report.pass()) {
                results.add(new ItemResult(i, title, "skipped", "未达准入标准：" + report.issueText()));
                skipped++;
                continue;
            }

            if (!dryRun) {
                KnowledgeEntry entry = KnowledgeEntry.builder()
                        .id(UUID.randomUUID())
                        .companyId(companyId)
                        .origin(KnowledgeEntry.ORIGIN_EXTERNAL)
                        .sourceType(companyId == null
                                ? KnowledgeEntry.SOURCE_TYPE_PLATFORM : KnowledgeEntry.SOURCE_TYPE_TENANT)
                        .category(category)
                        .title(title.trim())
                        .content(content)
                        .version(1)
                        .status(KnowledgeEntry.STATUS_PENDING)
                        .source(source)
                        .sourceDocument(documentName(sourceName))
                        .createdBy(operatorId)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                knowledgeRepository.save(entry);
                writeAudit("import_knowledge", "knowledge", entry.getId(), Map.of("title", title, "book", book));
            }
            imported++;
            results.add(new ItemResult(i, title, "imported", null));
        }

        return resultMap(dryRun, results, imported, 0, skipped);
    }

    // ==================== 辅助 ====================

    private void assertItems(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(400, "没有可导入的条目，请粘贴 JSON 数组");
        }
        if (items.size() > MAX_ITEMS) {
            throw new BusinessException(400, "单次最多导入 " + MAX_ITEMS + " 条，请分批处理");
        }
    }

    private Map<String, Object> resultMap(boolean dryRun, List<ItemResult> results,
                                          int imported, int needsSupplement, int skipped) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dryRun", dryRun);
        m.put("total", results.size());
        m.put("imported", imported);
        m.put("needsSupplement", needsSupplement);
        m.put("skipped", skipped);
        m.put("items", results.stream().map(ItemResult::toMap).toList());
        return m;
    }

    /** 书名/资料名 + 条目自带出处；避免重复拼接 */
    private String composeSource(String sourceName, String itemSource) {
        String name = sourceName == null ? "" : sourceName.trim();
        String item = itemSource == null ? "" : itemSource.trim();
        if (name.isEmpty()) {
            return SourceText.truncate(item.isEmpty() ? null : item);
        }
        if (item.isEmpty()) {
            return SourceText.truncate(name);
        }
        return SourceText.truncate(item.startsWith(name) ? item : name + " · " + item);
    }

    /** 外部导入的来源文档/书名；空白视为手工录入；按列宽截断 */
    private String documentName(String sourceName) {
        return SourceText.truncateDocument(sourceName);
    }

    /** 列宽兜底：描述型字段超长截断（身份型字段如标题走跳过，避免静默改名） */
    private String cap(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 模型可能返回对象/数组，统一转 JSON 字符串 */
    private String jsonText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** JSONB 对象兜底：合法 JSON 保留，纯文本包装成 {"text": ...} */
    private String normalizeJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw.trim());
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(Map.of("text", raw.trim()));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** JSONB 数组兜底：数组保留，单句包装成单元素数组 */
    private String normalizeJsonArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw.trim());
            if (node.isArray()) {
                return objectMapper.writeValueAsString(node);
            }
        } catch (Exception ignored) {
            // 非 JSON，按单条处理
        }
        try {
            return objectMapper.writeValueAsString(List.of(raw.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    private void writeAudit(String action, String targetType, UUID targetId, Map<String, ?> detail) {
        try {
            UUID adminId = com.aiextract.config.TokenContext.get();
            auditLogRepository.save(AdminAuditLog.builder()
                    .id(UUID.randomUUID())
                    .adminId(adminId != null ? adminId : UUID.fromString("00000000-0000-0000-0000-000000000000"))
                    .action(action)
                    .targetType(targetType)
                    .targetId(targetId)
                    .detail(objectMapper.writeValueAsString(detail))
                    .createdAt(LocalDateTime.now())
                    .build());
        } catch (Exception e) {
            log.warn("导入审计写入失败 action={} targetId={}: {}", action, targetId, e.getMessage());
        }
    }
}
