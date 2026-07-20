package com.aiextract.scheduler;

import com.aiextract.repository.AnalyticsEventRepository;
import com.aiextract.repository.GrainRetrieveLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据清理定时任务 —— 删除过期日志，控制表数据量。
 *
 * <p>grain_retrieve_log: 每次 RAG 检索写 5 行，日活 100 × 10 轮 = 5000 行/天 ≈ 15 万/月
 * analytics_event: 前端埋点，体量可控但持续增长，统一 30 天清理</p>
 *
 * @author AI Extract Team
 * @since 2026-07-17
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataCleanupScheduler {

    private static final String LOG_RAG_CLEANUP_DONE = "清理过期RAG检索日志完成, {}行";
    private static final String LOG_ANALYTICS_CLEANUP_DONE = "清理过期埋点数据完成, {}行";

    private final GrainRetrieveLogRepository grainRetrieveLogRepository;
    private final AnalyticsEventRepository analyticsEventRepository;

    /** 每天凌晨 3 点清理 30 天前的 RAG 检索日志 */
    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupGrainRetrieveLog() {
        try {
            int deleted = grainRetrieveLogRepository.deleteOlderThan30Days();
            if (deleted > 0) { log.info(LOG_RAG_CLEANUP_DONE, deleted); }
        } catch (Exception e) {
            log.error("清理RAG检索日志失败", e);
        }
    }

    /** 每天凌晨 3:30 清理 30 天前的埋点数据 */
    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanupAnalyticsEvents() {
        try {
            int deleted = analyticsEventRepository.deleteOlderThan30Days();
            if (deleted > 0) { log.info(LOG_ANALYTICS_CLEANUP_DONE, deleted); }
        } catch (Exception e) {
            log.error("清理埋点数据失败", e);
        }
    }
}
