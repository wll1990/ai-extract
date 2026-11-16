package com.aiextract.service;

import com.aiextract.config.PromptLoader;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.EvaluationRunItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 评测单题裁判 —— 依据题目金标给分身回答打分，产出 {@code ai_score} 与业务结论。
 *
 * <p>分数由 LLM 给（四维 + 置信度），**结论由服务端按确定性规则判定**，
 * 避免同一份评分在不同次运行得出不同 pass/fail：</p>
 * <ul>
 *   <li>命中红线 {@code forbidden_hits} → {@code fail}（人工必审）</li>
 *   <li>模型判 {@code undetermined}（金标不足/答非所问）→ {@code undetermined}</li>
 *   <li>{@code total_score >= pass-threshold} → {@code pass}，否则 {@code fail}</li>
 * </ul>
 *
 * <p>必审条件（对应计划 §七「复核策略」）：触红线、置信度低于阈值、总分落在及格线 ±band 的临界区。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationJudgeService {

    private static final String PROMPT_TEMPLATE = "eval_judge.md";
    private static final String EMPTY_PLACEHOLDER = "（无）";

    private final ChatStreamAdapter chatStreamAdapter;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    /** 及格线（0-100） */
    @Value("${app.evaluation.pass-threshold:60}")
    private int passThreshold;

    /** 置信度下限：低于此值一律转人工复核 */
    @Value("${app.evaluation.review-conf-threshold:0.60}")
    private double reviewConfThreshold;

    /** 临界分带宽：总分与及格线差值 ≤ band 视为临界，转人工复核 */
    @Value("${app.evaluation.review-band:5}")
    private int reviewBand;

    /**
     * 对单题回答评分。
     *
     * @param runItem 执行记录（用于取题目版本快照信息）
     * @param item    题目（金标来源）
     * @param answer  分身回答
     * @return 标准化评分 JSON + 业务结论 + 是否必审
     */
    public JudgeOutcome judge(EvaluationRunItem runItem, EvaluationItem item, String answer, String purpose) {
        String prompt = promptLoader.format(PROMPT_TEMPLATE, promptVars(item, answer, purpose));
        String raw = chatStreamAdapter.chat(prompt);
        JsonNode parsed = parse(raw, item.getId());

        int total = clampScore(parsed.path("total_score").asInt(0));
        double confidence = clampConfidence(parsed.path("confidence").asDouble(0));
        boolean forbiddenHit = parsed.path("forbidden_hits").isArray()
                && !parsed.path("forbidden_hits").isEmpty();
        String suggestion = parsed.path("verdict_suggestion").asText("");
        String reason = parsed.path("reason").asText("");

        String verdict = decideVerdict(forbiddenHit, suggestion, total);
        boolean critical = Math.abs(total - passThreshold) <= reviewBand;
        // 模型按要点覆盖判 fail、而服务端按分数判 pass：属评分口径分歧，必须人工定夺，
        // 否则「漏掉核心要点但分数够线」的题会被静默判过
        boolean modelDisagrees = EvaluationRunItem.VERDICT_FAIL.equalsIgnoreCase(suggestion)
                && EvaluationRunItem.VERDICT_PASS.equals(verdict);
        boolean needReview = forbiddenHit || confidence < reviewConfThreshold || critical || modelDisagrees;

        String scoreJson = normalizeScore(parsed, total, confidence, verdict, needReview,
                critical, forbiddenHit, modelDisagrees);
        String ruleCheckJson = buildRuleCheck(parsed, forbiddenHit);
        log.info("评测评分 itemId={} runItemId={} total={} conf={} verdict={} needReview={}",
                item.getId(), runItem.getId(), total, confidence, verdict, needReview);
        return new JudgeOutcome(scoreJson, ruleCheckJson, verdict, needReview, reason);
    }

    /** 规则检查结果：红线命中项与引用合规性（供详情抽屉与报告追溯）。 */
    private String buildRuleCheck(JsonNode parsed, boolean forbiddenHit) {
        ObjectNode out = objectMapper.createObjectNode();
        out.set("forbidden_hits", parsed.path("forbidden_hits"));
        out.put("forbidden_hit", forbiddenHit);
        out.put("citation_ok", parsed.path("citation_ok").asBoolean(true));
        out.set("hit_points", parsed.path("hit_points"));
        out.set("missed_points", parsed.path("missed_points"));
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException("规则检查结果序列化失败", e);
        }
    }

    /** 服务端确定性判定：红线优先，其次模型 undetermined，最后按分数与及格线。 */
    private String decideVerdict(boolean forbiddenHit, String suggestion, int total) {
        if (forbiddenHit) {
            return EvaluationRunItem.VERDICT_FAIL;
        }
        if (EvaluationRunItem.VERDICT_UNDETERMINED.equalsIgnoreCase(suggestion)) {
            return EvaluationRunItem.VERDICT_UNDETERMINED;
        }
        return total >= passThreshold ? EvaluationRunItem.VERDICT_PASS : EvaluationRunItem.VERDICT_FAIL;
    }

    private Map<String, String> promptVars(EvaluationItem item, String answer, String purpose) {
        // HashMap 而非 Map.of：金标字段允许为 null
        Map<String, String> vars = new HashMap<>();
        vars.put("purpose", nvl(purpose, ""));
        vars.put("question", nvl(item.getQuestion(), ""));
        vars.put("context", nvl(item.getContext(), EMPTY_PLACEHOLDER));
        vars.put("answer", nvl(answer, ""));
        vars.put("expected_points", nvl(item.getExpectedPoints(), EMPTY_PLACEHOLDER));
        vars.put("forbidden_points", nvl(item.getForbiddenPoints(), EMPTY_PLACEHOLDER));
        vars.put("citation_requirement", nvl(item.getCitationRequirement(), "null"));
        vars.put("scene_tag", nvl(item.getSceneTag(), EMPTY_PLACEHOLDER));
        return vars;
    }

    /** 解析模型输出；解析失败直接抛异常（由 Worker 记为 error 并保留原始信息，不造假分数）。 */
    private JsonNode parse(String raw, java.util.UUID itemId) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("评分返回为空 itemId=" + itemId);
        }
        String cleaned = raw.trim()
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "");
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            if (!node.isObject()) {
                throw new IllegalStateException("评分结果不是 JSON 对象");
            }
            return node;
        } catch (Exception e) {
            throw new IllegalStateException("评分解析失败 itemId=" + itemId + ": " + e.getMessage(), e);
        }
    }

    /**
     * 落库的评分 JSON：保留模型原始四维与要点明细，追加服务端计算字段。
     *
     * <p>{@code confidence} 是计划 §十 的硬要求；{@code need_review} / {@code critical}
     * 让复核与报告能解释「为什么这题要人看」。</p>
     */
    private String normalizeScore(JsonNode parsed, int total, double confidence, String verdict,
                                  boolean needReview, boolean critical, boolean forbiddenHit,
                                  boolean modelDisagrees) {
        ObjectNode out = parsed.deepCopy();
        out.put("total_score", total);
        out.put("confidence", confidence);
        out.put("verdict", verdict);
        out.put("need_review", needReview);
        out.put("critical", critical);
        out.put("forbidden_hit", forbiddenHit);
        out.put("model_disagrees", modelDisagrees);
        out.put("pass_threshold", passThreshold);
        out.put("review_conf_threshold", reviewConfThreshold);
        out.put("review_band", reviewBand);
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            throw new IllegalStateException("评分序列化失败", e);
        }
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private double clampConfidence(double confidence) {
        return Math.max(0, Math.min(1, confidence));
    }

    private String nvl(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 评分结果：评分 JSON + 规则检查 JSON + 业务结论 + 是否必审 + 判分理由。 */
    public record JudgeOutcome(String aiScoreJson, String ruleCheckJson, String verdict,
                               boolean needReview, String reason) {
    }
}
