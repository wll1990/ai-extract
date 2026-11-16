package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.TrainingBatchAssignRequest;
import com.aiextract.dto.TrainingCertifyRequest;
import com.aiextract.dto.TrainingDeadlineExtendRequest;
import com.aiextract.dto.TrainingRemediationRequest;
import com.aiextract.dto.TrainingRevokeCertificationRequest;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingCertification;
import com.aiextract.model.TrainingTask;
import com.aiextract.model.TrainingTaskRecord;
import com.aiextract.service.TrainingAssignmentService;
import com.aiextract.service.TrainingCertificationService;
import com.aiextract.service.TrainingDeadlineExtensionService;
import com.aiextract.service.TrainingRemediationService;
import com.aiextract.service.TrainingReviewService;
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
 * 培训分配管理 Controller —— 批量分配与取消。
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/admin/training/assignments")
@RequiredArgsConstructor
public class AdminTrainingAssignmentController {

    private final TrainingAssignmentService assignmentService;
    private final TrainingReviewService reviewService;
    private final TrainingCertificationService certificationService;
    private final TrainingRemediationService remediationService;
    private final TrainingDeadlineExtensionService deadlineExtensionService;

    /** 认证确认：必修全过后由主管/导师确认上岗；重复确认幂等 */
    @PostMapping("/{id}/certify")
    public ApiResponse<Map<String, Object>> certify(@PathVariable UUID id,
                                                    @Valid @RequestBody(required = false)
                                                    TrainingCertifyRequest request) {
        TrainingCertificationService.CertificationResult result = certificationService.certify(
                id, request == null ? null : request.getNote());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assignmentId", result.assignment().getId().toString());
        body.put("cohortId", result.assignment().getCohortId().toString());
        body.put("learnerId", result.assignment().getLearnerId().toString());
        body.put("learnerName", result.learnerName());
        body.put("status", result.assignment().getStatus());
        body.put("alreadyCertified", result.alreadyCertified());
        body.put("certification", com.aiextract.dto.CertificationView.of(
                result.certification(), result.confirmedByName()));
        return ApiResponse.success(body);
    }

    /** 撤销认证：认证记录追加 revoked 历史，分配回到待认证 */
    @PostMapping("/{id}/certification/revoke")
    public ApiResponse<Map<String, Object>> revokeCertification(
            @PathVariable UUID id,
            @Valid @RequestBody TrainingRevokeCertificationRequest request) {
        TrainingCertificationService.RevocationResult result =
                certificationService.revoke(id, request.getReason());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assignmentId", result.assignment().getId().toString());
        body.put("status", result.assignment().getStatus());
        body.put("certification", com.aiextract.dto.CertificationView.of(
                result.revoked(), null, result.revokedByName()));
        return ApiResponse.success(body);
    }

    /** 安排补训：在原分配下为失败任务追加下一轮 */
    @PostMapping("/{id}/remediation")
    public ApiResponse<Map<String, Object>> createRemediation(
            @PathVariable UUID id,
            @Valid @RequestBody TrainingRemediationRequest request) {
        TrainingRemediationService.RemediationResult result = remediationService.create(
                id, request.getRecordId(), request.getReason(), request.getDeadline());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assignmentId", result.assignment().getId().toString());
        body.put("recordId", result.record().getId().toString());
        body.put("roundNo", result.roundNo());
        body.put("status", result.record().getStatus());
        body.put("deadline", result.assignment().getDeadline());
        return ApiResponse.success(body);
    }

    /** 误判重做：对已通过的任务追加一轮（不重置旧结论，认证门槛按最新轮重算） */
    @PostMapping("/{id}/reopen")
    public ApiResponse<Map<String, Object>> reopen(
            @PathVariable UUID id,
            @Valid @RequestBody TrainingRemediationRequest request) {
        TrainingRemediationService.RemediationResult result = remediationService.reopen(
                id, request.getRecordId(), request.getReason(), request.getDeadline());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assignmentId", result.assignment().getId().toString());
        body.put("recordId", result.record().getId().toString());
        body.put("roundNo", result.roundNo());
        body.put("roundType", result.record().getRoundType());
        body.put("status", result.record().getStatus());
        body.put("deadline", result.assignment().getDeadline());
        return ApiResponse.success(body);
    }

    /** 免修/豁免：管理员确认任务无需完成（计入认证满足，不计入通过数） */
    @PostMapping("/{id}/records/{recordId}/waive")
    public ApiResponse<Map<String, Object>> waive(
            @PathVariable UUID id,
            @PathVariable UUID recordId,
            @RequestBody Map<String, String> body) {
        var record = remediationService.waive(id, recordId, body.get("reason"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assignmentId", record.getAssignmentId().toString());
        result.put("recordId", record.getId().toString());
        result.put("status", record.getStatus());
        result.put("waivedBy", record.getWaivedBy() == null ? null : record.getWaivedBy().toString());
        result.put("waivedReason", record.getWaivedReason());
        return ApiResponse.success(result);
    }

    /** 改绑到其他批次（兜底操作，必须 force=true + 理由；无常规 UI） */
    @PostMapping("/{id}/rebind")
    public ApiResponse<Map<String, Object>> rebind(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        Object rawCohort = body == null ? null : body.get("targetCohortId");
        if (rawCohort == null) {
            throw new com.aiextract.exception.BusinessException(400, "缺少 targetCohortId");
        }
        boolean force = body.get("force") instanceof Boolean b && b;
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        var created = assignmentService.rebind(id, UUID.fromString(String.valueOf(rawCohort)),
                force, reason);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assignmentId", created.getId().toString());
        result.put("cohortId", created.getCohortId().toString());
        result.put("status", created.getStatus());
        return ApiResponse.success(result);
    }

    /** 延长分配截止（只允许向后、只影响进行中的考试尝试） */
    @PostMapping("/{id}/deadline")
    public ApiResponse<Map<String, Object>> extendDeadline(
            @PathVariable UUID id,
            @Valid @RequestBody TrainingDeadlineExtendRequest request) {
        TrainingDeadlineExtensionService.ExtensionResult result =
                deadlineExtensionService.extend(id, request.getDeadline(), request.getReason());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assignmentId", result.assignment().getId().toString());
        body.put("oldDeadline", result.oldDeadline());
        body.put("deadline", result.newDeadline());
        body.put("updatedAttempts", result.updatedAttempts());
        return ApiResponse.success(body);
    }

    /**
     * 学员的任务与尝试清单（复核抽屉）。
     *
     * <p>批次详情只给聚合计数，复核人需要从这里拿到 attemptId 才能打开证据。</p>
     */
    @GetMapping("/{id}/attempts")
    public ApiResponse<Map<String, Object>> listAttempts(@PathVariable UUID id) {
        TrainingReviewService.AssignmentAttempts detail = reviewService.listAssignmentAttempts(id);
        TrainingAssignment assignment = detail.assignment();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assignmentId", assignment.getId().toString());
        body.put("learnerId", assignment.getLearnerId().toString());
        body.put("status", assignment.getStatus());
        body.put("totalTasks", assignment.getTotalTasks());
        body.put("requiredTotal", assignment.getRequiredTotal());
        body.put("completedTasks", assignment.getCompletedTasks());
        body.put("passedTasks", assignment.getPassedTasks());
        body.put("requiredPassed", assignment.getRequiredPassed());
        body.put("deadline", assignment.getDeadline());
        body.put("certificationHistory", detail.certificationHistory().stream().map(history -> {
            TrainingCertification c = history.certification();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId().toString());
            row.put("status", c.getStatus());
            row.put("confirmedBy", c.getConfirmedBy() != null ? c.getConfirmedBy().toString() : null);
            row.put("confirmedByName", history.confirmedByName());
            row.put("confirmedAt", c.getConfirmedAt());
            row.put("note", c.getNote());
            row.put("revokedBy", c.getRevokedBy() != null ? c.getRevokedBy().toString() : null);
            row.put("revokedByName", history.revokedByName());
            row.put("revokedAt", c.getRevokedAt());
            row.put("revokeReason", c.getRevokeReason());
            row.put("createdAt", c.getCreatedAt());
            return row;
        }).toList());
        body.put("tasks", detail.items().stream().map(item -> {
            TrainingTaskRecord record = item.record();
            TrainingTask task = item.task();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recordId", record.getId().toString());
            m.put("recordStatus", record.getStatus());
            m.put("roundNo", record.getRoundNo());
            m.put("remediationReason", record.getRemediationReason());
            m.put("taskId", task != null ? task.getId().toString() : record.getTaskId().toString());
            m.put("type", task != null ? task.getType() : null);
            m.put("title", task != null ? task.getTitle() : null);
            m.put("phaseNo", task != null ? task.getPhaseNo() : null);
            m.put("phaseName", task != null ? task.getPhaseName() : null);
            m.put("orderNo", task != null ? task.getOrderNo() : null);
            m.put("attempts", item.attempts().stream().map(AdminTrainingAssignmentController::toAttemptRow).toList());
            m.put("rounds", item.rounds().stream().map(round -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("recordId", round.record().getId().toString());
                row.put("roundNo", round.record().getRoundNo());
                row.put("status", round.record().getStatus());
                row.put("remediationReason", round.record().getRemediationReason());
                row.put("effective", round.effective());
                row.put("attempts", round.attempts().stream()
                        .map(AdminTrainingAssignmentController::toAttemptRow).toList());
                return row;
            }).toList());
            return m;
        }).toList());
        return ApiResponse.success(body);
    }

    private static Map<String, Object> toAttemptRow(TrainingAttempt attempt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", attempt.getId().toString());
        m.put("attemptNo", attempt.getAttemptNo());
        m.put("type", attempt.getType());
        m.put("status", attempt.getStatus());
        // 待复核/评分中不下发暂定结论（与学员端同口径）
        m.put("result", TrainingAttempt.STATUS_FINALIZED.equals(attempt.getStatus())
                ? attempt.getResult() : null);
        m.put("startedAt", attempt.getStartedAt());
        m.put("submittedAt", attempt.getSubmittedAt());
        m.put("finishedAt", attempt.getFinishedAt());
        m.put("errorMessage", attempt.getErrorMessage());
        m.put("terminationReason", attempt.getTerminationReason());
        // error 也需要人工裁决（AI 评分失败），不能让它从复核队列消失
        m.put("needsReview", TrainingAttempt.STATUS_PENDING_REVIEW.equals(attempt.getStatus())
                || TrainingAttempt.STATUS_ERROR.equals(attempt.getStatus()));
        m.put("totalScore", totalScore(attempt.getScore()));
        return m;
    }

    /** 评分 JSON 里的总分；无评分/损坏返回 null，不阻断复核列表 */
    private static Integer totalScore(String scoreJson) {
        if (scoreJson == null || scoreJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(scoreJson);
            com.fasterxml.jackson.databind.JsonNode total = node.get("total_score");
            return total != null && total.isNumber() ? total.asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 批量分配学员。
     *
     * <p>返回逐人结果（assigned / skipped / failed + 原因），前端不能只弹一个 toast。</p>
     */
    @PostMapping("/batch")
    public ApiResponse<Map<String, Object>> batchAssign(
            @Valid @RequestBody TrainingBatchAssignRequest req) {
        TrainingAssignmentService.BatchResult result = assignmentService.batchAssign(req);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cohortId", result.cohortId().toString());
        body.put("assigned", result.assigned());
        body.put("skipped", result.skipped());
        body.put("failed", result.failed());
        body.put("results", result.results().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("learnerId", r.learnerId() != null ? r.learnerId().toString() : null);
            m.put("name", r.name());
            m.put("status", r.status());
            m.put("reason", r.reason());
            return m;
        }).toList());
        return ApiResponse.success(body);
    }

    /** 取消某个学员的分配（保留历史） */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable UUID id) {
        TrainingAssignment assignment = assignmentService.cancelAssignment(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", assignment.getId().toString());
        body.put("status", assignment.getStatus());
        return ApiResponse.success(body);
    }
}
