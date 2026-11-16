package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.service.TrainingAttemptService;
import com.aiextract.service.TrainingLearningService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 学员端任务 Controller —— 详情、开始、阅读确认、开始尝试。
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/me/training/task-records")
@RequiredArgsConstructor
public class MeTrainingTaskController {

    private final TrainingLearningService learningService;
    private final TrainingAttemptService attemptService;

    /** 任务详情（含资源正文、锁定原因） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getTask(@PathVariable UUID id) {
        return ApiResponse.success(toTaskMap(learningService.getTaskDetail(id)));
    }

    /** 开始任务（阅读/陪练/考试通用；已开始则幂等返回） */
    @PostMapping("/{id}/start")
    public ApiResponse<Map<String, Object>> startTask(@PathVariable UUID id) {
        return ApiResponse.success(toTaskMap(learningService.startTask(id)));
    }

    /** 阅读任务标记已读（只表示阅读确认） */
    @PostMapping("/{id}/acknowledge")
    public ApiResponse<Map<String, Object>> acknowledge(@PathVariable UUID id) {
        learningService.acknowledge(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id.toString());
        body.put("status", TrainingTaskRecord.STATUS_PASSED);
        return ApiResponse.success(body);
    }

    /** 开始/续接陪练或考试；已在评分/待复核时返回该尝试，不新建 */
    @PostMapping("/{id}/attempts")
    public ApiResponse<Map<String, Object>> startAttempt(@PathVariable UUID id) {
        return ApiResponse.success(MeTrainingAttemptController.toAttemptMap(
                attemptService.startAttempt(id)));
    }

    /** 该任务的尝试历史：交卷后离开页面也能找回成绩与复核理由 */
    @GetMapping("/{id}/attempts")
    public ApiResponse<Map<String, Object>> attempts(@PathVariable UUID id) {
        TrainingAttemptService.AttemptHistory history = attemptService.getRecordAttempts(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", history.task().getId().toString());
        body.put("maxAttempts", history.task().getMaxAttempts());
        body.put("usedAttempts", history.usedAttempts());
        body.put("remainingAttempts", history.remainingAttempts());
        body.put("attempts", history.attempts().stream().map(row -> {
            TrainingAttempt attempt = row.attempt();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", attempt.getId().toString());
            m.put("attemptNo", attempt.getAttemptNo());
            m.put("type", attempt.getType());
            m.put("status", attempt.getStatus());
            m.put("result", TrainingAttempt.STATUS_FINALIZED.equals(attempt.getStatus())
                    ? attempt.getResult() : null);
            m.put("startedAt", attempt.getStartedAt());
            m.put("submittedAt", attempt.getSubmittedAt());
            m.put("finishedAt", attempt.getFinishedAt());
            m.put("errorMessage", TrainingAttempt.STATUS_ERROR.equals(attempt.getStatus())
                    ? "评分失败，请重新开始一次尝试（如持续失败请联系管理员）" : null);
            if (row.latestReview() != null) {
                Map<String, Object> review = new LinkedHashMap<>();
                review.put("action", row.latestReview().getAction());
                review.put("reason", row.latestReview().getReason());
                review.put("createdAt", row.latestReview().getCreatedAt());
                m.put("latestReview", review);
            }
            return m;
        }).toList());
        return ApiResponse.success(body);
    }

    // ==================== 映射 ====================

    /** JSONB 文本 → 对象；空值 null，损坏原样返回，与其它接口保持同一形态 */
    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private Map<String, Object> toTaskMap(TrainingLearningService.TaskDetail detail) {
        TrainingTaskRecord record = detail.record();
        TrainingTask task = detail.task();
        TrainingAssignment assignment = detail.assignment();

        Map<String, Object> recordMap = new LinkedHashMap<>();
        recordMap.put("id", record.getId().toString());
        recordMap.put("status", record.getStatus());
        recordMap.put("roundNo", record.getRoundNo());
        recordMap.put("remediationReason", record.getRemediationReason());
        recordMap.put("acknowledgedAt", record.getAcknowledgedAt());
        recordMap.put("startedAt", record.getStartedAt());
        recordMap.put("finishedAt", record.getFinishedAt());

        Map<String, Object> taskMap = new LinkedHashMap<>();
        taskMap.put("id", task.getId().toString());
        taskMap.put("type", task.getType());
        taskMap.put("title", task.getTitle());
        taskMap.put("description", task.getDescription());
        taskMap.put("phaseNo", task.getPhaseNo());
        taskMap.put("phaseName", task.getPhaseName());
        taskMap.put("isRequired", task.getIsRequired());
        taskMap.put("allowHints", task.getAllowHints());
        taskMap.put("maxAttempts", task.getMaxAttempts());
        taskMap.put("passCriteria", parseJson(task.getPassCriteria()));
        taskMap.put("sceneTag", task.getSceneTag());
        taskMap.put("examItemCount", task.getExamItemCount());
        taskMap.put("examDurationMinutes", task.getExamDurationMinutes());

        Map<String, Object> assignmentMap = new LinkedHashMap<>();
        assignmentMap.put("id", assignment.getId().toString());
        assignmentMap.put("status", assignment.getStatus());
        assignmentMap.put("deadline", assignment.getDeadline());
        assignmentMap.put("totalTasks", assignment.getTotalTasks());
        assignmentMap.put("requiredTotal", assignment.getRequiredTotal());
        assignmentMap.put("completedTasks", assignment.getCompletedTasks());
        assignmentMap.put("requiredPassed", assignment.getRequiredPassed());
        assignmentMap.put("passedTasks", assignment.getPassedTasks());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("record", recordMap);
        body.put("task", taskMap);
        body.put("assignment", assignmentMap);
        body.put("resource", detail.resource());
        body.put("blockingReasons", detail.blockingReasons());
        body.put("overdue", detail.overdue());
        return body;
    }
}
