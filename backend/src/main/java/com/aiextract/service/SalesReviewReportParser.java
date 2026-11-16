package com.aiextract.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 严格解析复盘报告 JSON：引用必须存在，引文必须能在原段落中找到。
 *
 * <p>宁可整份失败重试，也不落一份带假引用的报告。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesReviewReportParser {

    private final ObjectMapper objectMapper;

    public record ParsedReport(String summary, String payloadJson) {
    }

    public ParsedReport parse(String raw, Set<UUID> segmentIds, Map<UUID, String> segmentTexts) {
        String json = stripFences(raw).trim();
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("模型输出不是合法 JSON", e);
        }
        String summary = text(root, "summary");
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("缺少 summary");
        }
        if (summary.length() > 200) {
            log.warn("summary 超过 200 字，已截断（原长 {}）", summary.length());
            summary = summary.substring(0, 200);
        }
        // 一句话结论也是结论，必须能回看原文，否则与「每条结论可引用」自相矛盾
        List<Map<String, Object>> summaryEvidence = evidenceArray(root.path("summaryEvidence"), segmentIds, segmentTexts);
        if (summaryEvidence.isEmpty()) {
            throw new IllegalArgumentException("summary 缺少有效引用");
        }

        List<Map<String, Object>> strengths = points(root, "strengths", segmentIds, segmentTexts, 8);
        List<Map<String, Object>> weaknesses = points(root, "weaknesses", segmentIds, segmentTexts, 8);
        List<Map<String, Object>> risks = points(root, "risks", segmentIds, segmentTexts, 8);
        List<Map<String, Object>> signals = signals(root, "customerSignals", segmentIds, segmentTexts, 8);
        List<Map<String, Object>> nextSteps = steps(root, "nextSteps", segmentIds, segmentTexts, 3);
        Map<String, Object> primaryAction = primaryAction(root, nextSteps, segmentIds, segmentTexts);
        List<Map<String, Object>> infoGaps = infoGaps(root, signals);
        Map<String, Object> leadDraft = leadDraft(root);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("summaryEvidence", summaryEvidence);
        payload.put("strengths", strengths);
        payload.put("weaknesses", weaknesses);
        payload.put("risks", risks);
        payload.put("customerSignals", signals);
        payload.put("primaryAction", primaryAction);
        payload.put("infoGaps", infoGaps);
        payload.put("nextSteps", nextSteps);
        payload.put("leadDraft", leadDraft);
        try {
            return new ParsedReport(summary, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException("报告序列化失败", e);
        }
    }

    private List<Map<String, Object>> points(JsonNode root, String field, Set<UUID> segmentIds,
                                             Map<UUID, String> texts, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode arr = root.path(field);
        if (!arr.isArray()) {
            return out;
        }
        if (arr.size() > max) {
            log.warn("{} 条数 {} 超过上限 {}，已截断", field, arr.size(), max);
        }
        for (JsonNode n : arr) {
            if (out.size() >= max) {
                break;
            }
            String point = text(n, "point");
            if (point == null || point.isBlank()) {
                continue;
            }
            List<Map<String, Object>> evidence = evidence(n, segmentIds, texts);
            if (evidence.isEmpty()) {
                throw new IllegalArgumentException(field + " 缺少有效引用");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("point", point);
            m.put("evidence", evidence);
            m.put("confidence", confidence(n));
            m.put("suggestion", text(n, "suggestion"));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> signals(JsonNode root, String field, Set<UUID> segmentIds,
                                              Map<UUID, String> texts, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode arr = root.path(field);
        if (!arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (out.size() >= max) {
                break;
            }
            String name = text(n, "field");
            if (name == null || name.isBlank()) {
                continue;
            }
            String sourceType = text(n, "sourceType");
            if (sourceType == null
                    || !Set.of("customer_words", "sales_paraphrase", "ai_inference", "unknown").contains(sourceType)) {
                sourceType = "unknown";
            }
            String value = text(n, "value");
            if ("unknown".equals(sourceType)) {
                value = null;
            }
            List<Map<String, Object>> evidence = evidence(n, segmentIds, texts);
            // 已知来源（客户原话/销售转述/AI 推断）必须有可回看的引用，不能编造客户事实
            if (!"unknown".equals(sourceType) && (evidence.isEmpty() || value == null || value.isBlank())) {
                throw new IllegalArgumentException("customerSignals 的 " + sourceType + " 缺少 value 或有效引用");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", name);
            m.put("value", value);
            m.put("sourceType", sourceType);
            m.put("evidence", evidence);
            m.put("confidence", confidence(n));
            out.add(m);
        }
        return out;
    }

    /** 五个客户信号字段缺失时的标准追问；模型没给 question 时兜底，不编造客户事实。 */
    private static final Map<String, String> DEFAULT_GAP_QUESTIONS = Map.of(
            "预算", "这个项目的预算范围大概是多少？由谁审批？",
            "决策人", "最终拍板的是您还是其他人？还需要谁一起参与评估？",
            "时间线", "您计划什么时候启动或上线？",
            "需求", "您现在最想解决的问题是哪一件？",
            "异议", "您目前最大的顾虑是什么？");

    /**
     * 唯一主行动。模型漏给时用第一条下一步兜底（只做确定性映射，不生成新内容）。
     */
    private Map<String, Object> primaryAction(JsonNode root, List<Map<String, Object>> nextSteps,
                                              Set<UUID> segmentIds, Map<UUID, String> texts) {
        JsonNode n = root.path("primaryAction");
        if (n.isObject() && text(n, "title") != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", text(n, "title"));
            m.put("channel", text(n, "channel"));
            int rawDays = n.path("dueInDays").isNumber() ? n.path("dueInDays").asInt() : 1;
            m.put("dueInDays", Math.max(1, Math.min(rawDays, 30)));
            m.put("content", text(n, "content"));
            m.put("evidence", evidence(n, segmentIds, texts));
            return m;
        }
        if (!nextSteps.isEmpty()) {
            Map<String, Object> m = new LinkedHashMap<>(nextSteps.get(0));
            int days = m.get("dueInDays") instanceof Number d ? d.intValue() : 1;
            m.put("dueInDays", Math.min(days, 3));
            return m;
        }
        return null;
    }

    /**
     * 本次没拿到、下次必须问的关键信息。只保留 customerSignals 里确实缺失的字段，
     * 模型多给的字段一律丢弃，避免把已确认的信息列成缺口。
     */
    private List<Map<String, Object>> infoGaps(JsonNode root, List<Map<String, Object>> signals) {
        java.util.LinkedHashSet<String> missing = new java.util.LinkedHashSet<>();
        for (Map<String, Object> s : signals) {
            Object value = s.get("value");
            if (value == null || String.valueOf(value).isBlank()
                    || "unknown".equals(s.get("sourceType"))) {
                String field = String.valueOf(s.get("field"));
                if (DEFAULT_GAP_QUESTIONS.containsKey(field)) {
                    missing.add(field);
                }
            }
        }
        Map<String, JsonNode> provided = new java.util.HashMap<>();
        JsonNode arr = root.path("infoGaps");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                String field = text(n, "field");
                if (field != null) {
                    provided.putIfAbsent(field, n);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String field : missing) {
            if (out.size() >= 5) {
                break;
            }
            JsonNode n = provided.get(field);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", field);
            m.put("why", n == null ? null : text(n, "why"));
            String question = n == null ? null : text(n, "question");
            m.put("question", question != null ? question : DEFAULT_GAP_QUESTIONS.get(field));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> steps(JsonNode root, String field, Set<UUID> segmentIds,
                                            Map<UUID, String> texts, int max) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode arr = root.path(field);
        if (!arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (out.size() >= max) {
                break;
            }
            String title = text(n, "title");
            if (title == null || title.isBlank()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", title);
            m.put("channel", text(n, "channel"));
            int rawDays = n.path("dueInDays").isNumber() ? n.path("dueInDays").asInt() : 3;
            m.put("dueInDays", Math.max(1, Math.min(rawDays, 30)));
            m.put("content", text(n, "content"));
            m.put("evidence", evidence(n, segmentIds, texts));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> evidence(JsonNode node, Set<UUID> segmentIds,
                                               Map<UUID, String> texts) {
        return evidenceArray(node.path("evidence"), segmentIds, texts);
    }

    private List<Map<String, Object>> evidenceArray(JsonNode arr, Set<UUID> segmentIds,
                                                    Map<UUID, String> texts) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode e : arr) {
            String sid = text(e, "segmentId");
            if (sid == null) {
                continue;
            }
            UUID id;
            try {
                id = UUID.fromString(sid);
            } catch (Exception ex) {
                throw new IllegalArgumentException("引用段落 ID 非法：" + sid);
            }
            if (!segmentIds.contains(id)) {
                throw new IllegalArgumentException("引用段落不属于本次会话：" + sid);
            }
            String quote = text(e, "quote");
            if (quote != null && !quote.isBlank()) {
                String source = squash(texts.getOrDefault(id, ""));
                String needle = squash(quote);
                // 引用可能跨段落切点：段落内找不到时再对比整篇原文
                boolean matched = source.contains(needle)
                        || squash(String.join("", texts.values())).contains(needle);
                if (!matched) {
                    throw new IllegalArgumentException("引用原文与段落不匹配：" + sid);
                }
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("segmentId", sid);
            if (quote != null) {
                m.put("quote", quote);
            }
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> leadDraft(JsonNode root) {
        JsonNode d = root.path("leadDraft");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", d.path("name").isTextual() ? d.path("name").asText() : null);
        out.put("contactName", d.path("contactName").isTextual() ? d.path("contactName").asText() : null);
        out.put("contactPhone", d.path("contactPhone").isTextual() ? d.path("contactPhone").asText() : null);
        out.put("needSummary", d.path("needSummary").isTextual() ? d.path("needSummary").asText() : null);
        return out;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() && !v.asText().isBlank() ? v.asText().trim() : null;
    }

    private String confidence(JsonNode node) {
        String c = text(node, "confidence");
        return c != null && Set.of("high", "medium", "low").contains(c) ? c : "low";
    }

    private String stripFences(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            if (first > 0) {
                s = s.substring(first + 1);
            }
            int last = s.lastIndexOf("```");
            if (last >= 0) {
                s = s.substring(0, last);
            }
        }
        return s;
    }

    private String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }
}
