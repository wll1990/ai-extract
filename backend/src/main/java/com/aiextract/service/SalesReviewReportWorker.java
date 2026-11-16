package com.aiextract.service;

import com.aiextract.common.AiErrorMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 复盘报告 Worker：领取 queued / 租约过期的 job，事务外生成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesReviewReportWorker {

    private final SalesReviewReportService reportService;
    private final SalesReviewReportGenerator generator;

    @Value("${app.sales.review.enabled:true}")
    private boolean enabled;

    @Value("${app.sales.review.worker-id:}")
    private String workerId;

    @Value("${app.sales.review.batch-size:2}")
    private int batchSize;

    private String owner;

    @PostConstruct
    void init() {
        this.owner = (workerId == null || workerId.isBlank())
                ? hostname() + "-" + ProcessHandle.current().pid()
                : workerId;
    }

    @Scheduled(fixedDelayString = "${app.sales.review.worker-interval-ms:5000}",
            initialDelayString = "${app.sales.review.worker-initial-delay-ms:15000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        // 先清理租约耗尽但没人再领取的 job，避免永久 generating
        reportService.sweepStale();
        List<UUID> ids = reportService.claimBatch(owner, batchSize);
        for (UUID id : ids) {
            try {
                generator.generate(id, owner);
            } catch (Exception e) {
                log.warn("复盘报告生成失败 reportId={}", id, e);
                reportService.fail(id, owner, AiErrorMapper.describe(e));
            }
        }
    }

    private String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
