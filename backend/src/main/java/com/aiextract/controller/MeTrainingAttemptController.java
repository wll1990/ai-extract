package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.SseAdapter;
import com.aiextract.dto.PracticeRespondRequest;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingTask;
import com.aiextract.service.TrainingAttemptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 学员端尝试 Controller —— 恢复、草稿、陪练消息、提示。
 *
 * <p><b>金标不下发</b>：题面统一由 {@link TrainingAttemptService.AttemptView#questions()} 提供，
 * 只含 items；expectedPoints / forbiddenPoints / scoringRule 永远不进入响应体。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/me/training/attempts")
@RequiredArgsConstructor
public class MeTrainingAttemptController {

    private final TrainingAttemptService attemptService;

    /** 恢复：刷新页面按 id 重取，不新建尝试 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getAttempt(@PathVariable UUID id) {
        return ApiResponse.success(toAttemptMap(attemptService.getAttempt(id)));
    }

    /** 交卷：幂等；提交后答案锁定并进入评分队列 */
    @PostMapping("/{id}/submit")
    public ApiResponse<Map<String, Object>> submit(@PathVariable UUID id) {
        return ApiResponse.success(toAttemptMap(attemptService.submit(id)));
    }

    /** 成绩：评分中/待复核/已定稿/失败都返回真实状态；考试明细已剔除金标 */
    @GetMapping("/{id}/result")
    public ApiResponse<Map<String, Object>> result(@PathVariable UUID id) {
        return ApiResponse.success(toResultMap(attemptService.getResult(id)));
    }

    /** 保存答题草稿（仅进行中可写） */
    @PutMapping("/{id}/answers")
    public ApiResponse<Map<String, Object>> saveAnswers(
            @PathVariable UUID id, @RequestBody Map<String, Object> body) {
        Object answers = body.get("answers");
        if (answers == null) {
            throw new com.aiextract.exception.BusinessException(400, "answers 不能为空");
        }
        String json;
        try {
            json = answers instanceof String s ? s
                    : new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(answers);
        } catch (Exception e) {
            throw new com.aiextract.exception.BusinessException(400, "answers 不是合法 JSON");
        }
        attemptService.saveAnswers(id, json);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id.toString());
        result.put("saved", true);
        return ApiResponse.success(result);
    }

    /** 陪练消息（SSE） */
    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable UUID id,
                                  @Valid @RequestBody PracticeRespondRequest request) {
        return SseAdapter.fromFlux(attemptService.messages(id, request));
    }

    /** 陪练对话历史：刷新/续接时恢复现场（仅本人可读） */
    @GetMapping("/{id}/messages")
    public ApiResponse<List<Map<String, Object>>> messages(@PathVariable UUID id) {
        return ApiResponse.success(attemptService.practiceMessages(id));
    }

    /** 提示（SSE）；考试任务服务端直接 403 */
    @PostMapping(value = "/{id}/hints", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter hint(@PathVariable UUID id) {
        return SseAdapter.fromFlux(attemptService.hints(id));
    }

    // ==================== 映射 ====================

    /** 学员可见的尝试视图；questionSnapshot 只输出 items */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** JSONB 文本 → 对象；空值返回 null，损坏则原样返回（便于排查而不是静默丢数据） */
    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    /** 答题 JSON 字符串 → 对象；空值返回空对象，损坏则原样返回 */
    private static Object parseAnswers(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return json;
        }
    }

    /** 成绩视图：不暴露复核人 ID，只给动作/理由/调整后分数 */
    static Map<String, Object> toResultMap(TrainingAttemptService.AttemptResultView view) {
        TrainingAttempt attempt = view.attempt();
        TrainingTask task = view.task();

        Map<String, Object> attemptMap = new LinkedHashMap<>();
        attemptMap.put("id", attempt.getId().toString());
        attemptMap.put("assignmentId", attempt.getAssignmentId().toString());
        attemptMap.put("taskRecordId", attempt.getTaskRecordId().toString());
        attemptMap.put("taskId", attempt.getTaskId().toString());
        attemptMap.put("type", attempt.getType());
        attemptMap.put("attemptNo", attempt.getAttemptNo());
        boolean finalized = TrainingAttempt.STATUS_FINALIZED.equals(attempt.getStatus());
        attemptMap.put("status", attempt.getStatus());
        // 待复核/评分中不提前下发暂定结论，避免「先看到 fail、复核后又 pass」
        attemptMap.put("result", finalized ? attempt.getResult() : null);
        attemptMap.put("startedAt", attempt.getStartedAt());
        attemptMap.put("submittedAt", attempt.getSubmittedAt());
        attemptMap.put("finishedAt", attempt.getFinishedAt());
        attemptMap.put("deadline", attempt.getDeadline());
        attemptMap.put("terminationReason", attempt.getTerminationReason());
        // 原始异常只给管理员看；学员拿到可操作文案
        attemptMap.put("errorMessage", TrainingAttempt.STATUS_ERROR.equals(attempt.getStatus())
                ? "评分失败，请等待主管复核或联系管理员" : null);

        Map<String, Object> taskMap = new LinkedHashMap<>();
        taskMap.put("id", task.getId().toString());
        taskMap.put("type", task.getType());
        taskMap.put("title", task.getTitle());
        taskMap.put("maxAttempts", task.getMaxAttempts());
        taskMap.put("passCriteria", parseJson(task.getPassCriteria()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attempt", attemptMap);
        body.put("task", taskMap);
        body.put("score", view.score());
        body.put("questions", view.questions());
        body.put("answers", view.answers());
        body.put("usedAttempts", view.usedAttempts());
        body.put("remainingAttempts", view.remainingAttempts());
        body.put("canRetry", view.canRetry());
        body.put("retryBlockedReason", view.retryBlockedReason());
        // 复核人身份与人工调整分不对学员下发；最终分已合并进 score
        body.put("reviews", view.reviews().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", r.getAction());
            m.put("reason", r.getReason());
            m.put("createdAt", r.getCreatedAt());
            return m;
        }).toList());
        return body;
    }

    static Map<String, Object> toAttemptMap(TrainingAttemptService.AttemptView view) {
        TrainingAttempt attempt = view.attempt();
        TrainingTask task = view.task();

        Map<String, Object> attemptMap = new LinkedHashMap<>();
        attemptMap.put("id", attempt.getId().toString());
        attemptMap.put("taskRecordId", attempt.getTaskRecordId().toString());
        attemptMap.put("taskId", attempt.getTaskId().toString());
        attemptMap.put("type", attempt.getType());
        attemptMap.put("attemptNo", attempt.getAttemptNo());
        attemptMap.put("status", attempt.getStatus());
        // 待复核/评分中不下发暂定结论
        attemptMap.put("result", TrainingAttempt.STATUS_FINALIZED.equals(attempt.getStatus())
                ? attempt.getResult() : null);
        attemptMap.put("errorMessage", TrainingAttempt.STATUS_ERROR.equals(attempt.getStatus())
                ? "评分失败，请重新开始一次尝试（如持续失败请联系管理员）" : null);
        attemptMap.put("startedAt", attempt.getStartedAt());
        attemptMap.put("submittedAt", attempt.getSubmittedAt());
        attemptMap.put("deadline", attempt.getDeadline());
        attemptMap.put("remainingSeconds", view.remainingSeconds());
        attemptMap.put("deadlineExceeded", view.deadlineExceeded());
        attemptMap.put("terminationReason", view.terminationReason());
        attemptMap.put("conversationId", attempt.getConversationId() != null
                ? attempt.getConversationId().toString() : null);

        Map<String, Object> taskMap = new LinkedHashMap<>();
        taskMap.put("id", task.getId().toString());
        taskMap.put("type", task.getType());
        taskMap.put("title", task.getTitle());
        taskMap.put("description", task.getDescription());
        taskMap.put("allowHints", task.getAllowHints());
        taskMap.put("maxAttempts", task.getMaxAttempts());
        taskMap.put("passCriteria", parseJson(task.getPassCriteria()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("attempt", attemptMap);
        body.put("task", taskMap);
        body.put("questions", view.questions());
        // 微练场景头（scenario/clientRole）；非微练为 null
        body.put("scenario", view.scenario());
        // 与 questions 保持同一形态：能解析就返回对象，避免前端一处 parse 一处不 parse
        body.put("answers", parseAnswers(attempt.getAnswers()));
        body.put("allowedActions", view.allowedActions());
        return body;
    }
}
