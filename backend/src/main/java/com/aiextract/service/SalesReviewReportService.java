package com.aiextract.service;

import com.aiextract.model.ReviewReport;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.ReviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 报告 job 的领取 / 完成 / 失败。短事务，LLM 在事务外。
 */
@Service
@RequiredArgsConstructor
public class SalesReviewReportService {

    private final ReviewReportRepository reportRepository;
    private final ReviewSessionRepository sessionRepository;
    private final TrainingNotificationService notificationService;
    private final SalesAuditService audit;

    @Value("${app.sales.review.max-attempts:3}")
    private int maxAttempts;

    @Value("${app.sales.review.lease-minutes:10}")
    private int leaseMinutes;

    @Transactional
    public List<UUID> claimBatch(String owner, int limit) {
        List<UUID> ids = reportRepository.lockClaimableIds(limit, maxAttempts);
        LocalDateTime expires = LocalDateTime.now().plusMinutes(leaseMinutes);
        return ids.stream()
                .filter(id -> reportRepository.claim(id, owner, expires, maxAttempts) == 1)
                .toList();
    }

    @Transactional
    public void complete(UUID id, String owner, String summary, String payload, String model) {
        int updated = reportRepository.complete(id, owner, summary, payload, model);
        if (updated == 0) {
            return;
        }
        ReviewReport r = reportRepository.findById(id).orElseThrow();
        audit.record("sales.report.ready", "review_report", id,
                Map.of("version", r.getVersion(), "model", model));
        sessionRepository.findById(r.getSessionId()).ifPresent(s -> {
            if ("active".equals(s.getStatus())) {
                s.setStatus("report_ready");
                s.setUpdatedAt(LocalDateTime.now());
                sessionRepository.save(s);
            }
            notificationService.notifyUser(s.getReviewerId(), s.getCompanyId(),
                    "sales_review_ready", "复盘报告已生成",
                    "「" + (s.getCustomerName() == null ? "本次对话" : s.getCustomerName())
                            + "」的复盘已生成，点击查看。",
                    "/sales/reviews/" + s.getId(), "review_report", r.getId(),
                    "sales:report:" + r.getId());
        });
    }

    @Transactional
    public void fail(UUID id, String owner, String error) {
        ReviewReport r = reportRepository.findById(id).orElseThrow();
        boolean exhausted = r.getAttempts() >= maxAttempts;
        int updated = reportRepository.finishFailed(id, owner, exhausted ? "failed" : "queued",
                error == null ? "未知错误" : truncate(error, 1000));
        // 租约已被其他 worker 接管时 updated=0，不能替别人发失败通知
        if (updated == 1 && exhausted) {
            notifyExhausted(r);
        }
    }

    /**
     * 租约过期且 attempts 用尽的报告不会再被 claim，必须主动置 failed，
     * 否则前端会永远轮询 generating（P1-1）。
     *
     * @return true 表示本次已置 failed，调用方可以创建新版本
     */
    @Transactional
    public boolean expireIfStale(ReviewReport r) {
        if (!"generating".equals(r.getStatus())
                || r.getAttempts() < maxAttempts
                || r.getLeaseExpiresAt() == null
                || !r.getLeaseExpiresAt().isBefore(LocalDateTime.now())) {
            return false;
        }
        if (reportRepository.expireStale(r.getId(), maxAttempts) != 1) {
            return false;
        }
        r.setStatus("failed");
        notifyExhausted(r);
        return true;
    }

    /** 批量清理卡死的报告，由 Worker 每轮先执行。 */
    @Transactional
    public int sweepStale() {
        int count = 0;
        for (UUID id : reportRepository.findStaleExhaustedIds(maxAttempts, 200)) {
            if (reportRepository.expireStale(id, maxAttempts) == 1) {
                reportRepository.findById(id).ifPresent(this::notifyExhausted);
                count++;
            }
        }
        return count;
    }

    private void notifyExhausted(ReviewReport r) {
        sessionRepository.findById(r.getSessionId()).ifPresent(s ->
                notificationService.notifyUser(s.getReviewerId(), s.getCompanyId(),
                        "sales_review_failed", "复盘报告生成失败",
                        "「" + (s.getCustomerName() == null ? "本次对话" : s.getCustomerName())
                                + "」的复盘连续失败，请稍后重试或联系管理员。",
                        "/sales/reviews/" + s.getId(), "review_report", r.getId(),
                        "sales:report-failed:" + r.getId()));
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
