package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.TrainingCohort;
import com.aiextract.service.TrainingAssignmentService;
import com.aiextract.service.TrainingLearningService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 学员端「我的培训」Controller。
 *
 * <p>挂在 {@code /me/training/**} 下：userId 只从 JWT 取，不接受任何「我指定谁」的参数。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@RestController
@RequestMapping("/me/training")
@RequiredArgsConstructor
public class MeTrainingController {

    private final TrainingAssignmentService assignmentService;
    private final TrainingLearningService learningService;
    private final com.aiextract.repository.TrainingAssignmentRepository assignmentRepository;
    private final com.aiextract.repository.TrainingCertificationRepository certificationRepository;
    private final com.aiextract.repository.TrainingCohortRepository cohortRepository;
    private final com.aiextract.repository.TrainingPlanRepository planRepository;
    private final com.aiextract.repository.UserRepository userRepository;

    /** 我的培训列表（含批次与计划名称） */
    @GetMapping("/assignments")
    public ApiResponse<Map<String, Object>> myAssignments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        TrainingAssignmentService.MyAssignments result = assignmentService.myAssignments(page, size);
        Page<TrainingAssignment> p = result.page();
        List<Map<String, Object>> content = p.getContent().stream().map(a -> {
            TrainingCohort cohort = result.cohorts().get(a.getCohortId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId().toString());
            m.put("cohortId", a.getCohortId().toString());
            m.put("cohortName", cohort != null ? cohort.getName() : null);
            m.put("planId", cohort != null ? cohort.getPlanId().toString() : null);
            m.put("planName", cohort != null ? result.planNames().get(cohort.getPlanId()) : null);
            m.put("planVersion", cohort != null ? cohort.getPlanVersion() : null);
            m.put("status", a.getStatus());
            m.put("totalTasks", a.getTotalTasks());
            m.put("requiredTotal", a.getRequiredTotal());
            m.put("completedTasks", a.getCompletedTasks());
            m.put("requiredPassed", a.getRequiredPassed());
            m.put("passedTasks", a.getPassedTasks());
            m.put("deadline", a.getDeadline());
            boolean overdue = a.getDeadline() != null
                    && a.getDeadline().isBefore(LocalDateTime.now())
                    && (TrainingAssignment.STATUS_NOT_STARTED.equals(a.getStatus())
                        || TrainingAssignment.STATUS_IN_PROGRESS.equals(a.getStatus()));
            m.put("overdue", overdue);
            m.put("remainingSeconds", a.getDeadline() == null ? null
                    : Math.max(0, Duration.between(LocalDateTime.now(), a.getDeadline()).getSeconds()));
            m.put("createdAt", a.getCreatedAt());
            // 无认证记录时显式下发 null，不省略 key
            m.put("certification", result.certifications().get(a.getId()));
            return m;
        }).toList();
        return ApiResponse.success(PageResponse.of(content, p, page, size));
    }

    /**
     * 学员下载自己的认证证书（HTML，可直接打印/另存为 PDF）。
     * 只有本人、且认证状态为 confirmed 才能访问；未认证返回 409。
     */
    @GetMapping("/assignments/{id}/certificate")
    public org.springframework.http.ResponseEntity<String> certificate(@PathVariable UUID id) {
        java.util.UUID me = com.aiextract.config.TokenContext.get();
        if (me == null) {
            throw new com.aiextract.exception.BusinessException(401, "未登录");
        }
        TrainingAssignment assignment = assignmentRepository.findById(id)
                .orElseThrow(() -> new com.aiextract.exception.BusinessException(404, "培训数据不存在或不可访问"));
        if (!me.equals(assignment.getLearnerId())) {
            throw new com.aiextract.exception.BusinessException(403, "只能下载自己的认证证书");
        }
        var cert = certificationRepository
                .findByAssignmentIdAndStatus(id, "confirmed")
                .orElseThrow(() -> new com.aiextract.exception.BusinessException(409, "尚未通过认证，暂无证书"));
        TrainingCohort cohort = cohortRepository.findById(assignment.getCohortId()).orElse(null);
        String planName = cohort == null ? "培训计划"
                : planRepository.findById(cohort.getPlanId()).map(p -> p.getName()).orElse("培训计划");
        String learnerName = userRepository.findById(me).map(u -> u.getName()).orElse("学员");
        String issued = cert.getConfirmedAt() == null ? ""
                : cert.getConfirmedAt().toLocalDate().toString();
        String certNo = "MF-" + id.toString().substring(0, 8).toUpperCase()
                + "-" + (issued.isEmpty() ? "" : issued.replace("-", ""));

        String html = """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                <meta charset="utf-8"/>
                <title>认证证书 - %s</title>
                <style>
                  @page { size: A4 landscape; margin: 0; }
                  body { margin: 0; font-family: "Songti SC", "SimSun", serif; background: #f8fafc; }
                  .cert { width: 297mm; height: 210mm; box-sizing: border-box; padding: 20mm 24mm;
                          background: #fff; position: relative; }
                  .frame { position: absolute; inset: 10mm; border: 3px double #1e3a8a; }
                  .inner { position: relative; text-align: center; height: 100%%;
                           display: flex; flex-direction: column; justify-content: center; }
                  h1 { font-size: 40px; letter-spacing: 12px; color: #1e3a8a; margin: 0 0 8mm; }
                  .sub { font-size: 15px; color: #64748b; letter-spacing: 4px; margin-bottom: 14mm; }
                  .name { font-size: 30px; font-weight: bold; border-bottom: 2px solid #1e3a8a;
                          display: inline-block; padding: 0 14mm 2mm; margin-bottom: 8mm; }
                  .text { font-size: 17px; line-height: 2; color: #334155; max-width: 190mm; margin: 0 auto; }
                  .plan { font-size: 22px; font-weight: bold; color: #0f172a; }
                  .meta { margin-top: 16mm; display: flex; justify-content: space-between;
                          font-size: 13px; color: #64748b; }
                  .seal { margin-top: 10mm; font-size: 14px; color: #1e3a8a; letter-spacing: 2px; }
                  @media print { body { background: #fff; } .no-print { display: none; } }
                  .no-print { position: fixed; top: 12px; right: 12px; }
                  .no-print button { padding: 8px 16px; border-radius: 8px; border: 1px solid #cbd5e1;
                                     background: #fff; cursor: pointer; }
                </style>
                </head>
                <body>
                  <div class="no-print"><button onclick="window.print()">打印 / 另存为 PDF</button></div>
                  <div class="cert"><div class="frame"></div><div class="inner">
                    <h1>认 证 证 书</h1>
                    <div class="sub">CERTIFICATE OF COMPLETION</div>
                    <div class="name">%s</div>
                    <div class="text">
                      已完成 <span class="plan">%s</span> 全部必修任务并通过导师复核，
                      <br/>经确认具备相应岗位能力，特颁此证。
                    </div>
                    <div class="seal">MindForge 培训认证</div>
                    <div class="meta"><span>证书编号：%s</span><span>颁发日期：%s</span></div>
                  </div></div>
                </body>
                </html>
                """.formatted(esc(planName), esc(learnerName), esc(planName), esc(certNo), esc(issued));

        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type", "text/html;charset=UTF-8")
                .header("Content-Disposition", "inline; filename=\"certificate-" + id + ".html\"")
                .body(html);
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 某次分配下的任务清单（含锁定原因与分配摘要），学员端唯一的任务发现入口 */
    @GetMapping("/assignments/{id}/tasks")
    public ApiResponse<Map<String, Object>> assignmentTasks(@PathVariable UUID id) {
        TrainingLearningService.AssignmentTasks result = learningService.listAssignmentTasks(id);
        TrainingAssignment assignment = result.assignment();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", assignment.getId().toString());
        summary.put("status", assignment.getStatus());
        summary.put("deadline", assignment.getDeadline());
        summary.put("overdue", assignment.getDeadline() != null
                && assignment.getDeadline().isBefore(LocalDateTime.now()) && assignment.isOpen());
        summary.put("totalTasks", assignment.getTotalTasks());
        summary.put("requiredTotal", assignment.getRequiredTotal());
        summary.put("completedTasks", assignment.getCompletedTasks());
        summary.put("requiredPassed", assignment.getRequiredPassed());
        summary.put("passedTasks", assignment.getPassedTasks());

        List<Map<String, Object>> tasks = result.items().stream().map(item -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recordId", item.record().getId().toString());
            m.put("status", item.record().getStatus());
            m.put("roundNo", item.record().getRoundNo());
            m.put("remediationReason", item.record().getRemediationReason());
            m.put("acknowledgedAt", item.record().getAcknowledgedAt());
            m.put("taskId", item.task().getId().toString());
            m.put("type", item.task().getType());
            m.put("title", item.task().getTitle());
            m.put("phaseNo", item.task().getPhaseNo());
            m.put("phaseName", item.task().getPhaseName());
            m.put("orderNo", item.task().getOrderNo());
            m.put("isRequired", item.task().getIsRequired());
            m.put("blockingReasons", item.blockingReasons());
            // 任务行直接显示绑定资源/题集名称，减少“必须点进去才知道是什么”
            m.put("resourceTitle", item.resourceTitle());
            // TR-02：学员事前可见的重考/补训规则
            int maxAttempts = item.task().getMaxAttempts() == null ? 1 : item.task().getMaxAttempts();
            // 只算真正占用次数的尝试（已定稿/待复核），与 startAttempt 口径一致
            int usedAttempts = item.usedAttempts();
            m.put("maxAttempts", maxAttempts);
            m.put("usedAttempts", usedAttempts);
            m.put("remainingAttempts", Math.max(0, maxAttempts - usedAttempts));
            m.put("passCriteria", item.task().getPassCriteria());
            m.put("examDurationMinutes", item.task().getExamDurationMinutes());
            if (item.latestAttempt() != null) {
                Map<String, Object> attempt = new LinkedHashMap<>();
                attempt.put("id", item.latestAttempt().getId().toString());
                attempt.put("attemptNo", item.latestAttempt().getAttemptNo());
                attempt.put("status", item.latestAttempt().getStatus());
                attempt.put("result", TrainingAttempt.STATUS_FINALIZED.equals(item.latestAttempt().getStatus())
                        ? item.latestAttempt().getResult() : null);
                attempt.put("submittedAt", item.latestAttempt().getSubmittedAt());
                m.put("latestAttempt", attempt);
            }
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assignment", summary);
        body.put("tasks", tasks);
        return ApiResponse.success(body);
    }
}
