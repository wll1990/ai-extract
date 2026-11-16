package com.aiextract.service;

import com.aiextract.common.AiErrorMapper;
import com.aiextract.model.SkillMessage;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.SkillMessageRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 培训评分主流程 —— 取证据 → 调 LLM（事务外）→ 交给短事务落库。
 *
 * <p>判定口径是**确定性**的：分数来自模型，是否通过由服务端按
 * {@code pass_criteria}（minScore / minPointCoverage）计算；红线、低置信、临界分
 * 一律转 {@code pending_review}，其余直接定稿。</p>
 *
 * <p>绝不允许在这里吞异常：任何判分失败都交给
 * {@link TrainingGradingStateService#failOrRequeue}，要么有限重试、要么落 error，
 * 不会写出假分数，也不会污染 {@link TrainingProgressService} 的计数。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingGradingService {

    /** 陪练对话喂给模型的上限（字符），超出只保留最近的对话，避免 prompt 无限膨胀 */
    private static final int MAX_TRANSCRIPT_CHARS = 20000;

    /** 明显的评分操纵特征；命中即转人工，不直接判 fail，避免误伤正常表述 */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("忽略(?:以上|之前|前面|上述|所有).{0,8}(?:规则|指令|提示|要求)"),
            Pattern.compile("ignore\\s+(?:all\\s+)?(?:previous|above|prior)\\s+(?:instructions|rules|prompts)",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:系统|system)\\s*(?:提示词|prompt)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("你现在是.{0,6}(?:评分|裁判|评委)"),
            Pattern.compile("(?:直接|必须|请)给.{0,2}(?:我)?满分"),
            Pattern.compile("verdict_suggestion", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\"?total_score\"?\\s*[:=]\\s*100"));

    private final TrainingAttemptRepository attemptRepository;
    private final TrainingTaskRepository taskRepository;
    private final SkillMessageRepository messageRepository;
    private final TrainingJudgeService judgeService;
    private final TrainingGradingStateService stateService;
    private final ObjectMapper objectMapper;

    /** 未配置 pass_criteria 时的默认及格线 */
    @Value("${app.training.grading.pass-threshold:60}")
    private int defaultPassThreshold;

    /** 置信度下限：低于此值一律转人工复核 */
    @Value("${app.training.grading.review-conf-threshold:0.60}")
    private double reviewConfThreshold;

    /** 临界分带宽：与及格线差值 ≤ band 视为临界，转人工复核 */
    @Value("${app.training.grading.review-band:5}")
    private int reviewBand;

    /** 单次尝试最多评分几次（含首次），防止死循环重试 */
    @Value("${app.training.grading.max-attempts:3}")
    private int maxGradingAttempts;

    /** 评分结论：业务结果 + 是否必须人工复核 + 落库评分 JSON */
    record Outcome(String result, boolean needReview, String scoreJson) {
    }

    /** 一条 {@code pass_criteria} 的解析结果 */
    record PassCriteria(int minScore, double minPointCoverage) {
    }

    /**
     * 评分一次已领取的尝试。任何异常转成 error/重试，不向调度器抛出。
     *
     * @param owner 当前租约持有者；落库时用它做归属守卫，防止过期租约的陈旧结果覆盖新结果
     */
    public void grade(UUID attemptId, String owner) {
        TrainingAttempt attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null || !TrainingAttempt.STATUS_GRADING.equals(attempt.getStatus())
                || !owner.equals(attempt.getLeaseOwner())) {
            return; // 已不在本 worker 的租约范围
        }
        try {
            TrainingTask task = taskRepository.findById(attempt.getTaskId())
                    .orElseThrow(() -> new IllegalStateException(
                            "培训任务不存在 taskId=" + attempt.getTaskId()));
            // 考试与微练共用题面/金标格式，评分口径完全一致；陪练单独走对话评估
            Outcome outcome = TrainingAttempt.TYPE_PRACTICE.equals(attempt.getType())
                    ? gradePractice(attempt, task)
                    : gradeExam(attempt, task);
            String status = outcome.needReview()
                    ? TrainingAttempt.STATUS_PENDING_REVIEW
                    : TrainingAttempt.STATUS_FINALIZED;
            stateService.complete(attemptId, attempt.getTaskRecordId(), owner, status,
                    outcome.result(), outcome.scoreJson());
        } catch (Exception e) {
            String message = AiErrorMapper.describe(e);
            log.error("培训评分失败 attemptId={}", attemptId, e);
            stateService.failOrRequeue(attemptId, owner, maxGradingAttempts, message);
        }
    }

    // ==================== 考试 ====================

    private Outcome gradeExam(TrainingAttempt attempt, TrainingTask task) throws Exception {
        JsonNode snapshot = readJson(attempt.getQuestionSnapshot(), "考试题面快照");
        JsonNode items = snapshot.path("items");
        if (!items.isArray() || items.isEmpty()) {
            throw new IllegalStateException("考试题面快照为空 attemptId=" + attempt.getId());
        }
        JsonNode answerKey = snapshot.path("answerKey");
        // 金标缺失时 coverage 会退化成 1.0，只要分数够线就自动通过——坏快照绝不能被静默判过
        if (!answerKey.isObject() || answerKey.isEmpty()) {
            throw new IllegalStateException("考试题面快照缺少 answerKey attemptId=" + attempt.getId());
        }
        JsonNode answers = readAnswers(attempt.getAnswers());
        PassCriteria criteria = parsePassCriteria(task);

        ArrayNode itemScores = objectMapper.createArrayNode();
        int scoreSum = 0;
        int answeredCount = 0;
        int expectedTotal = 0;
        int hitTotal = 0;
        double minConfidence = 1.0;
        boolean anyForbidden = false;
        boolean anyUndetermined = false;
        boolean modelDisagrees = false;
        boolean injectionSuspected = false;
        boolean anyUncheckedForbidden = false;
        boolean anyCitationFail = false;
        String firstReason = null;
        List<String> forbiddenSummary = new ArrayList<>();
        List<String> missedSummary = new ArrayList<>();

        for (JsonNode item : items) {
            String itemId = item.path("itemId").asText(null);
            if (itemId == null) {
                throw new IllegalStateException("题面快照缺少 itemId");
            }
            JsonNode key = answerKey.path(itemId);
            if (!key.isObject()) {
                throw new IllegalStateException("题面快照缺少题目金标 itemId=" + itemId);
            }
            expectedTotal += expectedCount(key);
            String answer = answers.path(itemId).asText("");
            injectionSuspected |= looksLikeInjection(answer);

            ObjectNode row = itemScores.addObject();
            row.put("itemId", itemId);
            row.put("orderNo", item.path("orderNo").asInt(0));

            if (answer.isBlank()) {
                // 未作答是「确定的漏答」，不需要也不应该交给模型猜
                row.put("answered", false);
                row.put("total_score", 0);
                row.put("confidence", 1.0);
                row.put("verdict", TrainingAttempt.RESULT_FAIL);
                row.put("reason", "未作答");
                row.set("hit_points", objectMapper.createArrayNode());
                row.set("missed_points", objectMapper.createArrayNode());
                row.set("forbidden_hits", objectMapper.createArrayNode());
                continue;
            }

            TrainingJudgeService.Judgement judged = judgeService.judgeExamItem(task, item, key, answer);
            // 只认金标里真实存在的要点，防止模型编造或复制提示语抬高覆盖率
            List<String> validHits = filterHits(judged.hitPoints(), expectedPoints(key));
            answeredCount++;
            scoreSum += judged.totalScore();
            minConfidence = Math.min(minConfidence, judged.confidence());
            hitTotal += validHits.size();
            anyForbidden |= judged.forbiddenHit();
            anyUndetermined |= judged.undetermined();
            anyUncheckedForbidden |= !judged.uncheckedForbidden().isEmpty();
            anyCitationFail |= judged.citationRequired() && !judged.citationOk();
            forbiddenSummary.addAll(judged.forbiddenHits());
            missedSummary.addAll(judged.missedPoints());
            if (firstReason == null) {
                firstReason = judged.reason();
            }

            String itemVerdict = itemVerdict(judged);
            row.put("answered", true);
            row.put("total_score", judged.totalScore());
            row.put("confidence", judged.confidence());
            row.put("verdict", itemVerdict);
            row.put("reason", judged.reason());
            row.set("hit_points", objectMapper.valueToTree(validHits));
            row.set("missed_points", objectMapper.valueToTree(judged.missedPoints()));
            row.set("forbidden_hits", objectMapper.valueToTree(judged.forbiddenHits()));
            if (TrainingAttempt.RESULT_FAIL.equals(normalizeSuggestion(judged.suggestion()))
                    && TrainingAttempt.RESULT_PASS.equals(itemVerdict)) {
                // 模型按要点判 fail、按分数却够线：口径分歧必须人看，不能静默判过
                modelDisagrees = true;
            }
        }

        // 分母是全部题目：未作答按 0 分计入平均，否则「跳过难题」反而能拿高分
        int totalScore = items.isEmpty() ? 0 : Math.round((float) scoreSum / items.size());
        double coverage = expectedTotal == 0 ? 1.0 : Math.min(1.0, (double) hitTotal / expectedTotal);
        String result = decideResult(anyForbidden, anyUndetermined, totalScore, coverage, criteria);
        // 临界不只看总分：覆盖率刚好卡在门槛附近同样属于「差一点」，必须人看
        boolean critical = !anyForbidden && !anyUndetermined
                && (Math.abs(totalScore - criteria.minScore()) <= reviewBand
                || (criteria.minPointCoverage() > 0
                    && Math.abs(coverage - criteria.minPointCoverage()) <= reviewBand / 100.0));
        boolean needReview = anyForbidden || anyUndetermined
                || minConfidence < reviewConfThreshold || critical || modelDisagrees
                || injectionSuspected || anyUncheckedForbidden || anyCitationFail;

        ObjectNode score = objectMapper.createObjectNode();
        score.put("mode", attempt.getType());
        score.put("total_score", totalScore);
        score.put("confidence", minConfidence);
        score.put("verdict", result);
        score.put("need_review", needReview);
        score.put("critical", critical);
        score.put("forbidden_hit", anyForbidden);
        score.put("model_disagrees", modelDisagrees);
        score.put("injection_suspected", injectionSuspected);
        score.put("unchecked_forbidden", anyUncheckedForbidden);
        score.put("citation_failed", anyCitationFail);
        score.put("pass_threshold", criteria.minScore());
        score.put("min_point_coverage", criteria.minPointCoverage());
        score.put("point_coverage", round3(coverage));
        score.put("answered_items", answeredCount);
        score.put("total_items", items.size());
        score.set("items", itemScores);
        // 给学员下一步行动：只列最关键的 3 条缺口，不倾倒整份要点
        if (!missedSummary.isEmpty()) {
            score.put("improvement", "建议重点补足：" + String.join("；", missedSummary.stream()
                    .distinct().limit(3).toList()));
        }
        score.put("reason", aggregateReason(anyForbidden, anyUndetermined, forbiddenSummary,
                totalScore, coverage, criteria, firstReason));
        return new Outcome(result, needReview, json(score));
    }

    // ==================== 陪练 ====================

    private Outcome gradePractice(TrainingAttempt attempt, TrainingTask task) throws Exception {
        Transcript transcript = transcript(attempt.getConversationId());
        PassCriteria criteria = parsePassCriteria(task);

        if (transcript.text().isBlank()) {
            // 没有任何对话证据：确定判不通过，不调用模型、不编造分数
            ObjectNode score = objectMapper.createObjectNode();
            score.put("mode", TrainingAttempt.TYPE_PRACTICE);
            score.put("total_score", 0);
            score.put("confidence", 1.0);
            score.put("verdict", TrainingAttempt.RESULT_FAIL);
            score.put("need_review", false);
            score.put("critical", false);
            score.put("forbidden_hit", false);
            score.put("pass_threshold", criteria.minScore());
            score.put("reason", "没有对话记录，无法证明能力达标");
            return new Outcome(TrainingAttempt.RESULT_FAIL, false, json(score));
        }

        TrainingJudgeService.Judgement judged = judgeService.judgePractice(task, transcript.text());
        String result = decideResult(judged.forbiddenHit(), judged.undetermined(),
                judged.totalScore(), 1.0, criteria);
        boolean critical = !judged.forbiddenHit() && !judged.undetermined()
                && Math.abs(judged.totalScore() - criteria.minScore()) <= reviewBand;
        boolean modelDisagrees = TrainingAttempt.RESULT_FAIL.equals(normalizeSuggestion(judged.suggestion()))
                && TrainingAttempt.RESULT_PASS.equals(result);
        boolean injectionSuspected = looksLikeInjection(transcript.text());
        boolean needReview = judged.forbiddenHit() || judged.undetermined()
                || judged.confidence() < reviewConfThreshold || critical || modelDisagrees
                || injectionSuspected;

        ObjectNode score = (ObjectNode) objectMapper.readTree(judged.rawJson());
        score.put("mode", TrainingAttempt.TYPE_PRACTICE);
        score.put("total_score", judged.totalScore());
        score.put("confidence", judged.confidence());
        score.put("verdict", result);
        score.put("need_review", needReview);
        score.put("critical", critical);
        score.put("forbidden_hit", judged.forbiddenHit());
        score.put("model_disagrees", modelDisagrees);
        score.put("injection_suspected", injectionSuspected);
        score.put("pass_threshold", criteria.minScore());
        score.put("rounds", transcript.rounds());
        if (!judged.missedPoints().isEmpty()) {
            score.put("improvement", "建议重点补足：" + String.join("；",
                    judged.missedPoints().stream().distinct().limit(3).toList()));
        }
        return new Outcome(result, needReview, json(score));
    }

    // ==================== 判定 ====================

    /**
     * 确定性结论：红线优先 → 模型未判定 → 分数与要点覆盖都过线。
     *
     * <p>{@code undetermined} 不当作 fail：它意味着证据不足以判定，必须人看。</p>
     */
    private String decideResult(boolean forbidden, boolean undetermined, int totalScore,
                                double coverage, PassCriteria criteria) {
        if (forbidden) {
            return TrainingAttempt.RESULT_FAIL;
        }
        if (undetermined) {
            return TrainingAttempt.RESULT_UNDETERMINED;
        }
        boolean scoreOk = totalScore >= criteria.minScore();
        boolean coverageOk = criteria.minPointCoverage() <= 0
                || coverage >= criteria.minPointCoverage();
        return scoreOk && coverageOk ? TrainingAttempt.RESULT_PASS : TrainingAttempt.RESULT_FAIL;
    }

    /**
     * 单题展示结论：直接采用模型 suggestion，避免用任务级总分阈值给单题下结论
     * （题均分过线、逐题却大面积标 fail 的自相矛盾）。
     */
    private String itemVerdict(TrainingJudgeService.Judgement judged) {
        if (judged.forbiddenHit()) {
            return TrainingAttempt.RESULT_FAIL;
        }
        String suggestion = judged.suggestion() == null ? "" : judged.suggestion().trim().toLowerCase();
        if ("undetermined".equals(suggestion)) {
            return TrainingAttempt.RESULT_UNDETERMINED;
        }
        if ("pass".equals(suggestion)) {
            return TrainingAttempt.RESULT_PASS;
        }
        if ("fail".equals(suggestion)) {
            return TrainingAttempt.RESULT_FAIL;
        }
        return judged.totalScore() >= defaultPassThreshold
                ? TrainingAttempt.RESULT_PASS : TrainingAttempt.RESULT_FAIL;
    }

    /** 金标要点列表（数组或纯文本） */
    private List<String> expectedPoints(JsonNode key) {
        List<String> points = new ArrayList<>();
        JsonNode expected = key == null ? null : key.get("expectedPoints");
        if (expected == null || expected.isNull()) {
            return points;
        }
        if (expected.isArray()) {
            for (JsonNode item : expected) {
                String text = item.isTextual() ? item.asText() : item.toString();
                if (text != null && !text.isBlank()) {
                    points.add(text.trim());
                }
            }
        } else if (!expected.asText().isBlank()) {
            points.add(expected.asText().trim());
        }
        return points;
    }

    /**
     * 只保留金标里真实存在的命中要点。
     *
     * <p>模型可能编造要点或原样复制提示语；覆盖率只按可核对的命中计算，
     * 同时天然限制 hit_points 数量不超过金标条数。</p>
     */
    private List<String> filterHits(List<String> hits, List<String> expected) {
        List<String> valid = new ArrayList<>();
        for (String hit : hits) {
            if (hit == null || hit.isBlank()) {
                continue;
            }
            String normalized = hit.trim().toLowerCase();
            boolean matched = expected.stream().anyMatch(point -> {
                String p = point.toLowerCase();
                return p.contains(normalized) || normalized.contains(p);
            });
            if (matched) {
                valid.add(hit.trim());
            }
        }
        return valid;
    }

    /**
     * 检测明显的评分操纵尝试。命中不直接判 fail（避免误伤正常表述），
     * 而是强制转人工复核——成绩不能由一段可疑文本自动决定。
     */
    private boolean looksLikeInjection(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(text).find()) {
                log.warn("判分材料命中疑似注入特征：{}", pattern.pattern());
                return true;
            }
        }
        return false;
    }

    private String normalizeSuggestion(String suggestion) {
        if ("pass".equalsIgnoreCase(suggestion)) {
            return TrainingAttempt.RESULT_PASS;
        }
        if ("fail".equalsIgnoreCase(suggestion)) {
            return TrainingAttempt.RESULT_FAIL;
        }
        return TrainingAttempt.RESULT_UNDETERMINED;
    }

    private String aggregateReason(boolean forbidden, boolean undetermined,
                                   List<String> forbiddenHits, int totalScore, double coverage,
                                   PassCriteria criteria, String firstReason) {
        if (forbidden) {
            return "命中红线：" + String.join("；", forbiddenHits);
        }
        if (undetermined) {
            return "存在无法判定的题目，需人工复核";
        }
        String base = "总分 " + totalScore + "（及格线 " + criteria.minScore() + "），要点覆盖 "
                + Math.round(coverage * 100) + "%";
        return firstReason == null || firstReason.isBlank() ? base : base + "；" + firstReason;
    }

    // ==================== 解析与组装 ====================

    private PassCriteria parsePassCriteria(TrainingTask task) {
        int minScore = defaultPassThreshold;
        double minCoverage = 0.0;
        String json = task.getPassCriteria();
        if (json == null || json.isBlank()) {
            return new PassCriteria(minScore, minCoverage);
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                log.warn("任务 {} 的 pass_criteria 不是对象，按默认及格线处理", task.getId());
                return new PassCriteria(minScore, minCoverage);
            }
            if (node.hasNonNull("minScore")) {
                minScore = Math.max(0, Math.min(100, node.get("minScore").asInt(minScore)));
            }
            if (node.hasNonNull("minPointCoverage")) {
                minCoverage = Math.max(0, Math.min(1, node.get("minPointCoverage").asDouble(0)));
            }
        } catch (Exception e) {
            log.warn("任务 {} 的 pass_criteria 无法解析，按默认及格线处理：{}", task.getId(), json);
        }
        return new PassCriteria(minScore, minCoverage);
    }

    /** 金标要点条数：数组/对象按元素数，文本按 1 条 */
    private int expectedCount(JsonNode key) {
        if (key == null || key.isMissingNode() || key.isNull()) {
            return 0;
        }
        JsonNode expected = key.get("expectedPoints");
        if (expected == null || expected.isNull()) {
            return 0;
        }
        if (expected.isArray() || expected.isObject()) {
            return expected.size();
        }
        return expected.asText().isBlank() ? 0 : 1;
    }

    /** 陪练材料：可读文本 + 学员发言轮数（一次查询算完，避免重复全量读） */
    private record Transcript(String text, int rounds) {
    }

    /** 对话记录 → 可读文本；超出上限只保留最近的部分 */
    private Transcript transcript(UUID conversationId) {
        if (conversationId == null) {
            return new Transcript("", 0);
        }
        List<SkillMessage> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversationId);
        if (messages.isEmpty()) {
            return new Transcript("", 0);
        }
        StringBuilder sb = new StringBuilder();
        int rounds = 0;
        for (SkillMessage message : messages) {
            boolean learner = "user".equals(message.getRole());
            if (learner) {
                rounds++;
            }
            String content = message.getContent() == null ? "" : message.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            sb.append(learner ? "学员" : "客户").append("：").append(content).append('\n');
        }
        String text = sb.toString().trim();
        if (text.length() > MAX_TRANSCRIPT_CHARS) {
            text = text.substring(text.length() - MAX_TRANSCRIPT_CHARS);
        }
        return new Transcript(text, rounds);
    }

    private JsonNode readJson(String json, String label) throws Exception {
        if (json == null || json.isBlank()) {
            throw new IllegalStateException(label + "为空");
        }
        return objectMapper.readTree(json);
    }

    /**
     * 答案解析：空值表示「未作答」（确定判 0 分），但**非法 JSON 必须显式失败**。
     *
     * <p>把损坏的 answers 当成空答会静默判 fail 并绕过复核，与「不造假成绩」冲突。</p>
     */
    private JsonNode readAnswers(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalStateException("答题记录不是 JSON 对象");
            }
            return node;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("答题记录无法解析: " + e.getMessage(), e);
        }
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("评分结果序列化失败", e);
        }
    }
}
