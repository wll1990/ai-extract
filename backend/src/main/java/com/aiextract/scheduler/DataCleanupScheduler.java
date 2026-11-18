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
    /** 试问会话是演练数据，保留 90 天足够回溯 */
    private static final int TRIAL_RETENTION_DAYS = 90;
    /** 纠偏记录是审计链路，保留 180 天 */
    private static final int REFINE_RETENTION_DAYS = 180;

    private final GrainRetrieveLogRepository grainRetrieveLogRepository;
    private final AnalyticsEventRepository analyticsEventRepository;
    private final com.aiextract.repository.KnowledgeCaseRetrieveLogRepository knowledgeCaseRetrieveLogRepository;
    private final com.aiextract.repository.TokenUsageLogRepository tokenUsageLogRepository;
    private final com.aiextract.repository.AdminAuditLogRepository adminAuditLogRepository;
    private final com.aiextract.repository.SkillTrialSessionRepository trialSessionRepository;
    private final com.aiextract.repository.SkillTrialMessageRepository trialMessageRepository;
    private final com.aiextract.repository.GrainRefineDraftRepository refineDraftRepository;
    private final com.aiextract.repository.AuthSessionRepository authSessionRepository;
    private final com.aiextract.repository.AgentSkillRepository agentSkillRepository;

    @org.springframework.beans.factory.annotation.Value("${app.auth.session.cleanup-retention-days:7}")
    private int authSessionRetentionDays;

    private static final int AUTH_SESSION_BATCH = 1000;

    /** DATA-02 主表保留月数（默认近 3 个月，历史搬归档不删除） */
    @org.springframework.beans.factory.annotation.Value("${app.retention.months:3}")
    private int retentionMonths;

    /**
     * 归档默认只统计不搬移：未经明确授权不得在生产库批量移动数据。
     * 需要真正归档时显式配置 app.retention.archive-enabled=true。
     */
    @org.springframework.beans.factory.annotation.Value("${app.retention.archive-enabled:false}")
    private boolean archiveEnabled;

    @org.springframework.beans.factory.annotation.Value("${app.retention.batch-size:5000}")
    private int archiveBatchSize;

    @org.springframework.beans.factory.annotation.Value("${app.retention.max-batches:100}")
    private int archiveMaxBatches;

    /** 自注入代理：每批独立短事务，避免一次搬几十万行长时间锁表 */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private DataCleanupScheduler self;

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

    /** 每天凌晨 3:10 清理 30 天前的知识/案例引用日志 */
    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = "0 10 3 * * ?")
    public void cleanupKnowledgeCaseRetrieveLog() {
        try {
            int deleted = knowledgeCaseRetrieveLogRepository.deleteOlderThan30Days();
            if (deleted > 0) { log.info("清理知识/案例引用日志完成, {}行", deleted); }
        } catch (Exception e) {
            log.error("清理知识/案例引用日志失败", e);
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

    /**
     * 每天凌晨 3:20 清理过期试问会话与纠偏草稿。
     *
     * <p>先删消息再删会话：外键已有 ON DELETE CASCADE，但按时间批量删时
     * 显式两步能避免大事务里 CASCADE 逐行回扫。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = "0 20 3 * * ?")
    public void cleanupTrialData() {
        try {
            java.time.LocalDateTime trialCutoff =
                    java.time.LocalDateTime.now().minusDays(TRIAL_RETENTION_DAYS);
            int messages = trialMessageRepository.deleteOlderThan(trialCutoff);
            int sessions = trialSessionRepository.deleteOlderThan(trialCutoff);
            java.time.LocalDateTime refineCutoff =
                    java.time.LocalDateTime.now().minusDays(REFINE_RETENTION_DAYS);
            int drafts = refineDraftRepository.deleteOlderThan(refineCutoff);
            if (messages + sessions + drafts > 0) {
                log.info("清理试问/纠偏数据完成：消息 {} 行，会话 {} 行，草稿 {} 行",
                        messages, sessions, drafts);
            }
        } catch (Exception e) {
            log.error("清理试问/纠偏数据失败", e);
        }
    }

    /**
     * 每天凌晨 3:50 物理删除绝对过期超过保留期的会话。
     *
     * <p>已撤销/过期会话是审计数据，按天分批删除，避免长事务锁表。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = "0 50 3 * * ?")
    public void cleanupAuthSessions() {
        try {
            java.time.Instant cutoff = java.time.Instant.now()
                    .minus(authSessionRetentionDays, java.time.temporal.ChronoUnit.DAYS);
            int total = 0;
            int deleted;
            do {
                deleted = authSessionRepository.deleteExpiredBefore(cutoff, AUTH_SESSION_BATCH);
                total += deleted;
            } while (deleted >= AUTH_SESSION_BATCH);
            if (total > 0) {
                log.info("清理过期会话完成, {}行", total);
            }
        } catch (Exception e) {
            log.error("清理过期会话失败", e);
        }
    }

    /**
     * 每天凌晨 3:55 清理技能历史版本：每个 key 只保留最近 10 个 archived。
     */
    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = "0 55 3 * * ?")
    public void cleanupAgentSkillVersions() {
        try {
            int deleted = agentSkillRepository.deleteOldArchivedVersions();
            if (deleted > 0) {
                log.info("清理技能历史版本完成, {}行", deleted);
            }
        } catch (Exception e) {
            log.error("清理技能历史版本失败", e);
        }
    }

    /**
     * DATA-02：每天凌晨 3:40 把超过保留期的 token 用量记录搬到归档表。
     * 主表只留近 N 个月，历史不删除；默认只统计不搬移。
     */
    @Scheduled(cron = "0 40 3 * * ?")
    public void archiveTokenUsage() {
        try {
            java.time.LocalDate cutoff = java.time.LocalDate.now().minusMonths(retentionMonths);
            long candidates = tokenUsageLogRepository.countByUsageDateBefore(cutoff);
            if (!archiveEnabled) {
                log.info("token 用量归档检查：{} 行超过 {} 个月（archive-enabled=false，仅统计不搬移）",
                        candidates, retentionMonths);
                return;
            }
            int total = 0;
            for (int i = 0; i < archiveMaxBatches; i++) {
                int moved = self.archiveTokenUsageBatch(cutoff, archiveBatchSize);
                if (moved <= 0) break;
                total += moved;
            }
            log.info("token 用量归档完成：搬移 {} 行到归档表（主表保留 {} 个月）", total, retentionMonths);
        } catch (Exception e) {
            log.error("token 用量归档失败", e);
        }
    }

    /** 单批搬移（独立短事务）。 */
    @Transactional(rollbackFor = Exception.class)
    public int archiveTokenUsageBatch(java.time.LocalDate cutoff, int limit) {
        return tokenUsageLogRepository.archiveBatch(cutoff, limit);
    }

    /**
     * DATA-02：每天凌晨 3:50 把超过保留期的审计日志搬到归档表。
     * 默认只统计不搬移。
     */
    @Scheduled(cron = "0 50 3 * * ?")
    public void archiveAuditLog() {
        try {
            java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusMonths(retentionMonths);
            long candidates = adminAuditLogRepository.countByCreatedAtBefore(cutoff);
            if (!archiveEnabled) {
                log.info("审计日志归档检查：{} 行超过 {} 个月（archive-enabled=false，仅统计不搬移）",
                        candidates, retentionMonths);
                return;
            }
            int total = 0;
            for (int i = 0; i < archiveMaxBatches; i++) {
                int moved = self.archiveAuditLogBatch(cutoff, archiveBatchSize);
                if (moved <= 0) break;
                total += moved;
            }
            log.info("审计日志归档完成：搬移 {} 行到归档表（主表保留 {} 个月）", total, retentionMonths);
        } catch (Exception e) {
            log.error("审计日志归档失败", e);
        }
    }

    /** 单批搬移（独立短事务）。 */
    @Transactional(rollbackFor = Exception.class)
    public int archiveAuditLogBatch(java.time.LocalDateTime cutoff, int limit) {
        return adminAuditLogRepository.archiveBatch(cutoff, limit);
    }
}
