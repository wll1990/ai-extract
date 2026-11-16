package com.aiextract.scheduler;

import com.aiextract.service.BackgroundJobHealthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 后台任务失败告警：每 15 分钟检查最近 1 小时失败数。
 *
 * <p>只输出结构化日志（可被日志平台告警），不自动重试、不改业务数据。
 * 具体失败仍通过各模块的 retry 接口处理。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackgroundJobAlertScheduler {

    private final BackgroundJobHealthService healthService;

    @Scheduled(fixedDelayString = "${app.ops.background-alert-interval-ms:900000}", initialDelay = 120000)
    public void check() {
        try {
            Map<String, Object> snapshot = healthService.snapshot(1);
            long total = ((Number) snapshot.get("totalFailures")).longValue();
            if (total == 0) {
                return;
            }
            Object jobs = snapshot.get("jobs");
            if (total >= 20) {
                log.error("后台任务失败告警 status=degraded window=1h total={} jobs={}", total, jobs);
            } else {
                log.warn("后台任务失败 status=warning window=1h total={} jobs={}", total, jobs);
            }
        } catch (Exception e) {
            log.error("后台任务健康检查失败", e);
        }
    }
}
