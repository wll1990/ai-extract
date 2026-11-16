package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.dto.EvaluationReviewRequest;
import com.aiextract.dto.EvaluationRunRequest;
import com.aiextract.model.EvaluationReport;
import com.aiextract.model.EvaluationReview;
import com.aiextract.model.EvaluationRun;
import com.aiextract.model.EvaluationRunItem;
import com.aiextract.service.EvaluationReviewService;
import com.aiextract.service.EvaluationRunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评测运行 API —— 建批次、进度、单题详情、取消、重试、复核、定稿、对比。
 *
 * <p>Controller 只做参数解析与响应组装；企业隔离、状态流转、事务都在 Service。</p>
 *
 * <p>权限：运行类操作 {@code EVAL_MANAGE}，复核与定稿 {@code EVAL_REVIEW}
 * （见 SecurityConfig，复核/定稿规则必须排在 {@code /admin/evaluations/**} 之前）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/admin/evaluations")
@RequiredArgsConstructor
public class AdminEvaluationRunController {

    private final EvaluationRunService runService;
    private final EvaluationReviewService reviewService;

    // ==================== 运行批次 ====================

    /** 运行历史（分页，可按题集/状态筛选） */
    @GetMapping("/runs")
    public ApiResponse<Map<String, Object>> listRuns(
            @RequestParam(required = false) UUID datasetId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<EvaluationRun> result = runService.listRuns(datasetId, status, page, size);
        List<Map<String, Object>> content = result.getContent().stream().map(this::toRunMap).toList();
        return ApiResponse.success(PageResponse.of(content, result, page, size));
    }

    /** 创建批次（幂等：同企业同 idempotencyKey 返回同一批次） */
    @PostMapping("/runs")
    public ApiResponse<Map<String, Object>> createRun(@Valid @RequestBody EvaluationRunRequest req) {
        return ApiResponse.success(toRunMap(runService.createRun(req)));
    }

    /** 质量基线趋势：同一数据集最近 N 个已完成批次的均分/通过率 */
    @GetMapping("/runs/trend")
    public ApiResponse<List<Map<String, Object>>> trend(@RequestParam UUID datasetId,
                                                        @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(runService.trend(datasetId, limit));
    }

    /** 批次详情（进度/计数/耗时） */
    @GetMapping("/runs/{id}")
    public ApiResponse<Map<String, Object>> getRun(@PathVariable UUID id) {
        return ApiResponse.success(toRunMap(runService.getRun(id)));
    }

    /** 已定稿报告：页面刷新后直接加载，避免只能靠再次点击定稿才能看到正文 */
    @GetMapping("/runs/{id}/report")
    public ApiResponse<Map<String, Object>> getReport(@PathVariable UUID id) {
        EvaluationReport report = runService.latestReport(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", report.getId().toString());
        m.put("runId", report.getRunId().toString());
        m.put("version", report.getVersion());
        m.put("status", report.getStatus());
        m.put("summary", report.getSummary());
        m.put("content", report.getContent());
        m.put("finalizedAt", report.getFinalizedAt());
        return ApiResponse.success(m);
    }

    /** 逐题执行列表（分页，按题序） */
    @GetMapping("/runs/{id}/items")
    public ApiResponse<Map<String, Object>> listRunItems(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<EvaluationRunItem> result = runService.listRunItems(id, page, size);
        Map<UUID, EvaluationRunService.FinalScore> scores = runService.finalScores(result.getContent());
        List<Map<String, Object>> content = result.getContent().stream().map(item -> {
            Map<String, Object> m = toRunItemMap(item);
            putFinalScore(m, scores.get(item.getId()));
            return m;
        }).toList();
        return ApiResponse.success(PageResponse.of(content, result, page, size));
    }

    /** 单题详情抽屉：回答、检索证据、规则检查、评分 */
    @GetMapping("/runs/{id}/items/{itemId}")
    public ApiResponse<Map<String, Object>> getRunItem(@PathVariable UUID id, @PathVariable UUID itemId) {
        EvaluationRunItem item = runService.getRunItem(id, itemId);
        Map<String, Object> map = toRunItemMap(item);
        putFinalScore(map, runService.finalScores(List.of(item)).get(item.getId()));
        List<Map<String, Object>> reviews = runService.listReviews(item.getId()).stream()
                .map(this::toReviewMap).toList();
        map.put("reviews", reviews);
        return ApiResponse.success(map);
    }

    /** 请求取消（在途批次由 Worker 收尾为 cancelled，历史证据保留） */
    @PostMapping("/runs/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancelRun(@PathVariable UUID id) {
        return ApiResponse.success(toRunMap(runService.cancelRun(id)));
    }

    /** 重试基础设施失败题（业务低分不可重试刷分） */
    @PostMapping("/runs/{id}/retry-failed")
    public ApiResponse<Map<String, Object>> retryFailed(@PathVariable UUID id) {
        int retried = runService.retryFailed(id);
        return ApiResponse.success(Map.of("retriedItems", retried));
    }

    /** 定稿：校验完整性与必审项，冻结报告（版本化） */
    @PostMapping("/runs/{id}/finalize")
    public ApiResponse<Map<String, Object>> finalizeRun(@PathVariable UUID id) {
        EvaluationReport report = runService.finalizeRun(id);
        return ApiResponse.success(toReportMap(report));
    }

    /** 两次运行逐题对比 */
    @GetMapping("/runs/compare")
    public ApiResponse<Map<String, Object>> compare(@RequestParam UUID left, @RequestParam UUID right) {
        EvaluationRunService.CompareResult result = runService.compare(left, right);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("left", result.leftId().toString());
        m.put("right", result.rightId().toString());
        m.put("sameCaliber", result.sameCaliber());
        m.put("caliberNote", result.caliberNote());
        m.put("nonCommonItems", result.nonCommonItems());
        m.put("diffs", result.diffs());
        return ApiResponse.success(m);
    }

    // ==================== 人工复核 ====================

    /** 单题人工复核（追加式，保留 AI 原分） */
    @PostMapping("/results/{runItemId}/reviews")
    public ApiResponse<Map<String, Object>> review(@PathVariable UUID runItemId,
                                                   @Valid @RequestBody EvaluationReviewRequest req) {
        return ApiResponse.success(toReviewMap(reviewService.review(runItemId, req)));
    }

    // ==================== 响应组装 ====================

    private Map<String, Object> toRunMap(EvaluationRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", run.getId().toString());
        m.put("datasetId", run.getDatasetId().toString());
        m.put("datasetVersion", run.getDatasetVersion());
        m.put("skillId", run.getSkillId().toString());
        m.put("purpose", run.getPurpose());
        m.put("status", run.getStatus());
        m.put("reviewStatus", run.getReviewStatus());
        m.put("totalItems", run.getTotalItems());
        m.put("completedItems", run.getCompletedItems());
        m.put("errorItems", run.getErrorItems());
        m.put("passedItems", run.getPassedItems());
        m.put("failedItems", run.getFailedItems());
        m.put("undeterminedItems", run.getUndeterminedItems());
        m.put("scoreAvg", run.getScoreAvg());
        m.put("scoreDetail", run.getScoreDetail());
        m.put("usageTokens", run.getUsageTokens());
        m.put("usageCost", run.getUsageCost());
        m.put("modelConfig", run.getModelConfig());
        m.put("startedAt", run.getStartedAt());
        m.put("finishedAt", run.getFinishedAt());
        m.put("createdBy", run.getCreatedBy());
        m.put("createdAt", run.getCreatedAt());
        return m;
    }

    private Map<String, Object> toRunItemMap(EvaluationRunItem item) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId().toString());
        m.put("runId", item.getRunId().toString());
        m.put("itemId", item.getItemId().toString());
        m.put("itemVersion", item.getItemVersion());
        m.put("status", item.getStatus());
        m.put("verdict", item.getVerdict());
        m.put("aiAnswer", item.getAiAnswer());
        m.put("retrievalEvidence", item.getRetrievalEvidence());
        m.put("ruleCheckResult", item.getRuleCheckResult());
        m.put("aiScore", item.getAiScore());
        m.put("errorMessage", item.getErrorMessage());
        m.put("attemptNo", item.getAttemptNo());
        m.put("createdAt", item.getCreatedAt());
        m.put("updatedAt", item.getUpdatedAt());
        return m;
    }

    /** 最终分：人工改分优先，前端据此展示，避免「改了分表格没变」 */
    private void putFinalScore(Map<String, Object> target, EvaluationRunService.FinalScore score) {
        if (score != null) {
            target.put("finalScore", score.score());
            target.put("scoreSource", score.source());
        }
    }

    private Map<String, Object> toReviewMap(EvaluationReview review) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", review.getId().toString());
        m.put("runItemId", review.getRunItemId().toString());
        m.put("reviewerId", review.getReviewerId() == null ? null : review.getReviewerId().toString());
        m.put("action", review.getAction());
        m.put("reason", review.getReason());
        m.put("originalScore", review.getOriginalScore());
        m.put("adjustedScore", review.getAdjustedScore());
        m.put("createdAt", review.getCreatedAt());
        return m;
    }

    private Map<String, Object> toReportMap(EvaluationReport report) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", report.getId().toString());
        m.put("runId", report.getRunId().toString());
        m.put("version", report.getVersion());
        m.put("status", report.getStatus());
        m.put("summary", report.getSummary());
        m.put("content", report.getContent());
        m.put("finalizedBy", report.getFinalizedBy() == null ? null : report.getFinalizedBy().toString());
        m.put("finalizedAt", report.getFinalizedAt());
        return m;
    }
}
