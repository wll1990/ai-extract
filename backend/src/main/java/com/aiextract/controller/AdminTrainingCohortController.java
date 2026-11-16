package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.dto.TrainingCohortRequest;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingPlan;
import com.aiextract.service.TrainingAssignmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 培训批次管理 Controller —— 建批次、列表、详情、进度。
 *
 * <p>进度与详情允许「该批次导师」访问（URL 级 TRAINING_REVIEW），
 * 具体是不是本人带的批次由 {@link com.aiextract.service.TrainingAccessService} 判定。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/admin/training/cohorts")
@RequiredArgsConstructor
public class AdminTrainingCohortController {

    private final TrainingAssignmentService assignmentService;
    private final com.aiextract.service.TrainingDeadlineExtensionService deadlineExtensionService;

    /** 批次列表 */
    @GetMapping
    public ApiResponse<Map<String, Object>> listCohorts(
            @RequestParam(required = false) UUID planId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<TrainingCohort> result = assignmentService.listCohorts(planId, status, page, size);
        List<Map<String, Object>> content = result.getContent().stream()
                .map(this::toCohortMap).toList();
        return ApiResponse.success(PageResponse.of(content, result, page, size));
    }

    /** 建批次（只能绑定已发布版本） */
    @PostMapping
    public ApiResponse<Map<String, Object>> createCohort(
            @Valid @RequestBody TrainingCohortRequest req) {
        return ApiResponse.success(toCohortMap(assignmentService.createCohort(req)));
    }

    /** 批次级延期：逐人独立事务，返回成功/跳过回执（一个人失败不影响整批） */
    @PostMapping("/{id}/deadline")
    public ApiResponse<Map<String, Object>> extendCohortDeadline(
            @PathVariable UUID id,
            @Valid @RequestBody com.aiextract.dto.TrainingDeadlineExtendRequest request) {
        var result = deadlineExtensionService.extendCohort(
                id, request.getDeadline(), request.getReason());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cohortId", result.cohortId().toString());
        body.put("deadline", result.newDeadline());
        body.put("extended", result.extended());
        body.put("skipped", result.skipped());
        body.put("items", result.items());
        return ApiResponse.success(body);
    }

    /** 批次状态流转（结班/取消/归档） */
    @PatchMapping("/{id}/status")
    public ApiResponse<Map<String, Object>> updateCohortStatus(
            @PathVariable UUID id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (status == null || status.isBlank()) {
            throw new com.aiextract.exception.BusinessException(400, "缺少 status");
        }
        return ApiResponse.success(toCohortMap(assignmentService.updateCohortStatus(id, status)));
    }

    /** 导师的「我的批次」：只返回本人任导师的批次 */
    @GetMapping("/mine")
    public ApiResponse<Map<String, Object>> myCohorts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<TrainingCohort> result = assignmentService.listMyCohorts(page, size);
        List<Map<String, Object>> content = result.getContent().stream()
                .map(this::toCohortMap).toList();
        return ApiResponse.success(PageResponse.of(content, result, page, size));
    }

    /** 批次详情 + 成员分配 + 计划信息 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getCohort(@PathVariable UUID id) {
        TrainingAssignmentService.CohortDetail detail = assignmentService.getCohortDetail(id);
        Map<String, Object> body = toCohortMap(detail.cohort());
        TrainingPlan plan = detail.plan();
        body.put("planName", plan != null ? plan.getName() : null);
        body.put("members", detail.assignments().stream()
                .map(a -> toAssignmentMap(a, detail.learners().get(a.getLearnerId()),
                        detail.certifications().get(a.getId())))
                .toList());
        return ApiResponse.success(body);
    }

    /** 批次进度：真实计数（给看板与导师用） */
    @GetMapping("/{id}/progress")
    public ApiResponse<Map<String, Object>> getProgress(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        TrainingAssignmentService.CohortProgress progress =
                assignmentService.cohortProgress(id, page, size, status, keyword);
        Map<String, Object> body = toCohortMap(progress.cohort());
        body.put("summary", progress.summary());
        body.put("page", progress.page());
        body.put("size", progress.size());
        body.put("total", progress.total());
        body.put("totalPages", progress.totalPages());
        body.put("learners", progress.learners().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("assignmentId", p.assignmentId().toString());
            m.put("learnerId", p.learnerId().toString());
            m.put("learnerName", p.name());
            m.put("status", p.status());
            m.put("totalTasks", p.totalTasks());
            m.put("requiredTotal", p.requiredTotal());
            m.put("completedTasks", p.completedTasks());
            m.put("requiredPassed", p.requiredPassed());
            m.put("passedTasks", p.passedTasks());
            m.put("deadline", p.deadline());
            m.put("overdue", p.overdue());
            m.put("pendingReview", p.pendingReview());
            m.put("pendingReviewOverdue", p.pendingReviewOverdue());
            m.put("pendingReviewHours", p.pendingReviewHours());
            m.put("canCertify", p.canCertify());
            m.put("certifyBlockedReason", p.certifyBlockedReason());
            m.put("certificationBlockers", p.certificationBlockers().stream().map(b -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("taskId", b.taskId().toString());
                row.put("title", b.title());
                row.put("recordStatus", b.recordStatus());
                return row;
            }).toList());
            m.put("certification", progress.certifications().get(p.assignmentId()));
            return m;
        }).toList());
        return ApiResponse.success(body);
    }

    // ==================== 映射 ====================

    private Map<String, Object> toCohortMap(TrainingCohort cohort) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", cohort.getId().toString());
        m.put("companyId", cohort.getCompanyId().toString());
        m.put("planId", cohort.getPlanId().toString());
        m.put("planVersion", cohort.getPlanVersion());
        m.put("name", cohort.getName());
        m.put("mentorId", cohort.getMentorId() != null ? cohort.getMentorId().toString() : null);
        m.put("deadline", cohort.getDeadline());
        m.put("status", cohort.getStatus());
        m.put("createdAt", cohort.getCreatedAt());
        return m;
    }

    private Map<String, Object> toAssignmentMap(TrainingAssignment a, com.aiextract.model.User learner,
                                                com.aiextract.dto.CertificationView certification) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId().toString());
        m.put("assignmentId", a.getId().toString());
        m.put("cohortId", a.getCohortId().toString());
        m.put("learnerId", a.getLearnerId().toString());
        m.put("learnerName", learner != null ? learner.getName() : null);
        m.put("status", a.getStatus());
        m.put("totalTasks", a.getTotalTasks());
        m.put("requiredTotal", a.getRequiredTotal());
        m.put("completedTasks", a.getCompletedTasks());
        m.put("requiredPassed", a.getRequiredPassed());
        m.put("passedTasks", a.getPassedTasks());
        m.put("deadline", a.getDeadline());
        m.put("overdue", a.getDeadline() != null && a.getDeadline().isBefore(LocalDateTime.now())
                && (TrainingAssignment.STATUS_NOT_STARTED.equals(a.getStatus())
                    || TrainingAssignment.STATUS_IN_PROGRESS.equals(a.getStatus())));
        m.put("certification", certification);
        m.put("createdAt", a.getCreatedAt());
        return m;
    }
}
