package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.TrainingReviewRequest;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingReview;
import com.aiextract.model.TrainingTask;
import com.aiextract.service.TrainingReviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 培训尝试复核 Controller —— 管理侧详情（含金标）与复核。
 *
 * <p>详情只走 {@link TrainingReviewService} 的归属链（attempt → record → assignment → cohort），
 * 复核权限由 URL 级 {@code TRAINING_REVIEW} + 服务层 {@code assertCanReview} 双重把关。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/admin/training/attempts")
@RequiredArgsConstructor
public class AdminTrainingAttemptController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TrainingReviewService reviewService;

    /** 管理侧尝试详情：题面金标、学员答案、AI 评分明细、复核历史 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getAttempt(@PathVariable UUID id) {
        TrainingReviewService.AdminAttemptDetail detail = reviewService.getAttemptDetail(id);
        TrainingAttempt attempt = detail.attempt();
        TrainingTask task = detail.task();

        Map<String, Object> attemptMap = new LinkedHashMap<>();
        attemptMap.put("id", attempt.getId().toString());
        attemptMap.put("assignmentId", attempt.getAssignmentId().toString());
        attemptMap.put("taskId", attempt.getTaskId().toString());
        attemptMap.put("taskRecordId", attempt.getTaskRecordId().toString());
        attemptMap.put("type", attempt.getType());
        attemptMap.put("attemptNo", attempt.getAttemptNo());
        attemptMap.put("status", attempt.getStatus());
        attemptMap.put("result", attempt.getResult());
        attemptMap.put("startedAt", attempt.getStartedAt());
        attemptMap.put("submittedAt", attempt.getSubmittedAt());
        attemptMap.put("finishedAt", attempt.getFinishedAt());
        attemptMap.put("deadline", attempt.getDeadline());
        attemptMap.put("errorMessage", attempt.getErrorMessage());
        attemptMap.put("terminationReason", attempt.getTerminationReason());
        attemptMap.put("gradingAttempts", attempt.getGradingAttempts());
        attemptMap.put("conversationId", attempt.getConversationId() != null
                ? attempt.getConversationId().toString() : null);
        // 考试进行中不下发金标；复核只需要 pending_review/finalized/error 的证据
        attemptMap.put("questionSnapshot", detail.exposeAnswerKey()
                ? parse(attempt.getQuestionSnapshot()) : null);
        attemptMap.put("answers", parse(attempt.getAnswers()));
        attemptMap.put("score", parse(attempt.getScore()));

        Map<String, Object> taskMap = new LinkedHashMap<>();
        taskMap.put("id", task.getId().toString());
        taskMap.put("type", task.getType());
        taskMap.put("title", task.getTitle());
        taskMap.put("description", task.getDescription());
        taskMap.put("allowHints", task.getAllowHints());
        taskMap.put("maxAttempts", task.getMaxAttempts());
        taskMap.put("passCriteria", parse(task.getPassCriteria()));

        Map<String, Object> learnerMap = new LinkedHashMap<>();
        learnerMap.put("id", detail.assignment().getLearnerId().toString());
        learnerMap.put("name", detail.learner() != null ? detail.learner().getName() : null);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attempt", attemptMap);
        body.put("task", taskMap);
        body.put("learner", learnerMap);
        body.put("cohort", Map.of(
                "id", detail.cohort().getId().toString(),
                "name", detail.cohort().getName() != null ? detail.cohort().getName() : ""));
        body.put("reviews", detail.reviews().stream()
                .map(r -> toReviewMap(r, detail.reviewerNames())).toList());
        // 陪练复核必须能看原对话；导师没有会话模块权限，这里内联
        body.put("messages", detail.messages().stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId().toString());
            row.put("role", m.getRole());
            row.put("roleLabel", m.getRoleLabel());
            row.put("content", m.getContent());
            row.put("createdAt", m.getCreatedAt());
            return row;
        }).toList());
        return ApiResponse.success(body);
    }

    /** 评分失败后重跑：仅 error 状态，重置重试计数 */
    @PostMapping("/{id}/regrade")
    public ApiResponse<Map<String, Object>> regrade(@PathVariable UUID id) {
        TrainingAttempt attempt = reviewService.regrade(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptId", attempt.getId().toString());
        body.put("status", attempt.getStatus());
        return ApiResponse.success(body);
    }

    /** 复核：pass/reject，理由必填；只允许复核 pending_review 的尝试 */
    @PostMapping("/{id}/reviews")
    public ApiResponse<Map<String, Object>> review(@PathVariable UUID id,
                                                   @Valid @RequestBody TrainingReviewRequest request) {
        TrainingReviewService.ReviewResult result = reviewService.review(id, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attemptId", result.attempt().getId().toString());
        body.put("status", result.attempt().getStatus());
        body.put("result", result.attempt().getResult());
        body.put("recordStatus", result.recordStatus());
        body.put("review", toReviewMap(result.review(), Map.of()));
        return ApiResponse.success(body);
    }

    private Map<String, Object> toReviewMap(TrainingReview review, Map<UUID, String> reviewerNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", review.getId().toString());
        m.put("reviewerId", review.getReviewerId() != null ? review.getReviewerId().toString() : null);
        m.put("reviewerName", review.getReviewerId() != null
                ? reviewerNames.get(review.getReviewerId()) : null);
        m.put("action", review.getAction());
        m.put("reason", review.getReason());
        m.put("originalScore", parse(review.getOriginalScore()));
        m.put("adjustedScore", parse(review.getAdjustedScore()));
        m.put("createdAt", review.getCreatedAt());
        return m;
    }

    /** JSONB 文本 → 结构化下发；损坏时原样返回，便于排查而不是静默丢数据 */
    private Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return MAPPER.convertValue(node, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
