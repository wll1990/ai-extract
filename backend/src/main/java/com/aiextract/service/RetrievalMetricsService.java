package com.aiextract.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 检索指标计算 —— 把「题目金标（retrieval_expected）」与「实际检索证据（retrieval_evidence）」比对，
 * 算出 Context Precision@K / Recall@K / MRR。
 *
 * <p>回答「评测集到底有没有测出召回问题」：</p>
 * <ul>
 *   <li>Recall@K：金标条目中被检回的比例</li>
 *   <li>Precision@K：检回条目中命中金标的比例（K=本次实际检回条数）</li>
 *   <li>MRR：第一个命中条目排名的倒数（越小说明命中越靠后）</li>
 * </ul>
 *
 * <p>匹配规则（确定性、可解释）：金标条目归一化后，被任一检回条目的标题/场景标签包含即命中。
 * 不是语义相似度 —— 语义相似会让「没检回金标」的题也显示命中，指标就失去意义。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalMetricsService {

    private final ObjectMapper objectMapper;

    /**
     * 计算单题检索指标。
     *
     * @param retrievalExpectedJson 题目金标（JSON 数组字符串，未配置返回 null）
     * @param evidenceJson          检索证据（EvaluationAnswerService 冻结的结构）
     * @return 指标；金标为空时返回 null（该题不参与召回统计）
     */
    public Metrics compute(String retrievalExpectedJson, String evidenceJson) {
        List<String> expected = parseExpected(retrievalExpectedJson);
        if (expected.isEmpty()) {
            return null;
        }
        List<Retrieved> retrieved = parseRetrieved(evidenceJson);
        // 按相似度降序排，排名用于 MRR（相似度缺失的排在后面）
        retrieved.sort(Comparator.comparingDouble((Retrieved r) -> r.similarity).reversed());

        boolean[] matched = new boolean[expected.size()];
        int hitRetrieved = 0;
        int firstHitRank = 0;
        for (int i = 0; i < retrieved.size(); i++) {
            boolean thisHit = false;
            String label = normalize(retrieved.get(i).label);
            for (int e = 0; e < expected.size(); e++) {
                if (!matched[e] && label.contains(normalize(expected.get(e)))) {
                    matched[e] = true;
                    thisHit = true;
                }
            }
            if (thisHit) {
                hitRetrieved++;
                if (firstHitRank == 0) {
                    firstHitRank = i + 1;
                }
            }
        }

        int hitExpected = 0;
        List<String> missed = new ArrayList<>();
        for (int e = 0; e < expected.size(); e++) {
            if (matched[e]) {
                hitExpected++;
            } else {
                missed.add(expected.get(e));
            }
        }

        double recall = (double) hitExpected / expected.size();
        double precision = retrieved.isEmpty() ? 0 : (double) hitRetrieved / retrieved.size();
        double mrr = firstHitRank == 0 ? 0 : 1.0 / firstHitRank;

        return new Metrics(expected.size(), retrieved.size(), hitExpected, hitRetrieved,
                round(recall), round(precision), round(mrr), missed);
    }

    /** 把单题指标写进规则检查 JSON 的 retrieval_metrics 字段（供详情与聚合使用）。 */
    public String attachTo(String ruleCheckJson, Metrics metrics) {
        if (metrics == null) {
            return ruleCheckJson;
        }
        try {
            ObjectNode root = ruleCheckJson == null || ruleCheckJson.isBlank()
                    ? objectMapper.createObjectNode()
                    : (ObjectNode) objectMapper.readTree(ruleCheckJson);
            ObjectNode m = root.putObject("retrieval_metrics");
            m.put("expectedCount", metrics.expectedCount());
            m.put("retrievedCount", metrics.retrievedCount());
            m.put("hitExpected", metrics.hitExpected());
            m.put("hitRetrieved", metrics.hitRetrieved());
            m.put("recall", metrics.recall());
            m.put("precisionAtK", metrics.precisionAtK());
            m.put("mrr", metrics.mrr());
            ArrayNode missed = m.putArray("missedExpected");
            metrics.missedExpected().forEach(missed::add);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("检索指标写入规则检查失败", e);
        }
    }

    private List<String> parseExpected(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> result = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode item : node) {
                    String s = item.asText("").trim();
                    if (!s.isEmpty()) {
                        result.add(s);
                    }
                }
            }
            return result;
        } catch (Exception e) {
            // 金标格式错误不阻断执行：该题按「无金标」处理，但不静默 —— 打日志
            log.warn("retrieval_expected 解析失败，本题不计入召回统计: {}", e.getMessage());
            return List.of();
        }
    }

    /** 从证据里取出所有检回条目（颗粒场景标签 / 知识标题 / 案例标题与场景标签），统一为可匹配文本。 */
    private List<Retrieved> parseRetrieved(String evidenceJson) {
        List<Retrieved> list = new ArrayList<>();
        if (evidenceJson == null || evidenceJson.isBlank()) {
            return list;
        }
        try {
            JsonNode root = objectMapper.readTree(evidenceJson);
            collect(root.path("grains"), "sceneTag", list);
            collect(root.path("knowledge"), "title", list);
            collect(root.path("cases"), "title", list);
            collect(root.path("cases"), "sceneTag", list);
        } catch (Exception e) {
            log.warn("retrieval_evidence 解析失败，按零命中处理: {}", e.getMessage());
        }
        return list;
    }

    private void collect(JsonNode array, String field, List<Retrieved> list) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode node : array) {
            String label = node.path(field).asText("").trim();
            if (label.isEmpty()) {
                continue;
            }
            list.add(new Retrieved(label, node.path("similarity").asDouble(0)));
        }
    }

    /** 归一化：只保留字母/数字/汉字并小写，消除空白与标点差异。 */
    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private record Retrieved(String label, double similarity) {
    }

    /** 单题检索指标。 */
    public record Metrics(int expectedCount, int retrievedCount, int hitExpected, int hitRetrieved,
                          double recall, double precisionAtK, double mrr, List<String> missedExpected) {
    }
}
