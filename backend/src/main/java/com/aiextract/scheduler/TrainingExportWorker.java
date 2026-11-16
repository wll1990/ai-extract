package com.aiextract.scheduler;

import com.aiextract.service.TrainingExportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 培训导出 worker —— 领取 running 任务并生成 CSV。
 *
 * <p>单条失败只影响自己；租约过期后可被重新领取（attempts 计数供排查）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingExportWorker {

    private final TrainingExportService exportService;

    @Value("${app.training.export.enabled:true}")
    private boolean enabled;

    @Value("${app.training.export.batch-size:3}")
    private int batchSize;

    /**
     * 租约 owner。多实例部署必须唯一，否则旧实例能覆盖已接管者。
     * 未显式配置时用「主机名 + 进程号」兜底。
     */
    @Value("${app.training.export.worker-id:}")
    private String workerId;

    @jakarta.annotation.PostConstruct
    void initWorkerId() {
        if (workerId != null && !workerId.isBlank()) {
            return;
        }
        try {
            workerId = "training-export-" + java.net.InetAddress.getLocalHost().getHostName()
                    + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            workerId = "training-export-" + UUID.randomUUID();
        }
    }

    @Scheduled(fixedDelayString = "${app.training.export.worker-interval-ms:15000}",
            initialDelayString = "${app.training.export.worker-initial-delay-ms:15000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        List<UUID> claimed;
        try {
            claimed = exportService.claimBatch(workerId, batchSize);
        } catch (Exception e) {
            log.error("导出任务领取失败", e);
            return;
        }
        for (UUID jobId : claimed) {
            try {
                // CSV 生成与存储不带事务；状态落库由下面的短事务方法完成
                TrainingExportService.ProcessOutcome outcome =
                        exportService.process(jobId, workerId);
                if (outcome == null) {
                    log.debug("导出任务租约已失效，跳过 jobId={}", jobId);
                    continue;
                }
                exportService.complete(jobId, workerId, outcome);
            } catch (Exception e) {
                log.warn("导出任务处理失败 jobId={}", jobId, e);
                try {
                    exportService.fail(jobId, workerId, truncate(e.getMessage()));
                } catch (Exception inner) {
                    log.error("导出失败状态写入失败 jobId={}", jobId, inner);
                }
            }
        }
    }

    private String truncate(String message) {
        String value = message == null || message.isBlank() ? "导出失败" : message;
        return value.length() > 1000 ? value.substring(0, 1000) + "…" : value;
    }
}
