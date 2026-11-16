package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.repository.TrainingAssignmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.UUID;

/**
 * 培训评分执行器 —— 定时领取「已提交」的尝试并判分。
 *
 * <p>与评测执行器（{@code EvaluationWorker}）同构：任务状态只存 PostgreSQL，
 * 领取走 {@code FOR UPDATE SKIP LOCKED} + 租约，进程中断后租约到期可被重新领取。</p>
 *
 * <p>单实例单线程（{@code fixedDelay}），多实例安全由行锁保证；
 * 企业级并发上限暂未实现，部署多实例前需补。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingGradingWorker {

    private final TrainingGradingLeaseService leaseService;
    private final TrainingGradingService gradingService;
    private final TrainingAssignmentRepository assignmentRepository;

    /** 本实例的 worker 标识：用于租约归属与心跳续租 */
    private final String workerId = buildWorkerId();

    @Value("${app.training.grading.worker-enabled:true}")
    private boolean workerEnabled;

    @Value("${app.training.grading.batch-size:5}")
    private int batchSize;

    /**
     * 主循环：领取一批 → 逐条判分。
     *
     * <p>fixedDelay 保证上一轮结束后才安排下一轮，不会叠加执行。</p>
     */
    @Scheduled(fixedDelayString = "${app.training.grading.worker-interval-ms:5000}",
            initialDelayString = "${app.training.grading.worker-initial-delay-ms:20000}")
    public void tick() {
        if (!workerEnabled) {
            return;
        }
        List<UUID> claimed;
        try {
            claimed = leaseService.claim(workerId, batchSize);
        } catch (Exception e) {
            // 领取失败只影响本轮；下一轮继续，不能让异常逃出调度线程
            log.error("培训评分领取失败", e);
            return;
        }
        for (UUID attemptId : claimed) {
            try {
                // 评分会调 LLM；带上企业上下文，企业 Key 才能真正生效
                UUID companyId = assignmentRepository.findCompanyIdByAttemptId(attemptId);
                TokenContext.callWith(null, companyId, () -> gradingService.grade(attemptId, workerId));
            } catch (Exception e) {
                // grade() 内部已处理业务失败；这里兜底数据访问异常，避免一条拖垮整批
                log.error("培训评分处理异常 attemptId={}", attemptId, e);
            }
        }
    }

    /** 心跳：续租本 worker 名下所有在途尝试，避免长任务被误判超时 */
    @Scheduled(fixedDelayString = "${app.training.grading.heartbeat-interval-ms:60000}",
            initialDelayString = "${app.training.grading.heartbeat-initial-delay-ms:30000}")
    public void heartbeat() {
        if (!workerEnabled) {
            return;
        }
        try {
            int renewed = leaseService.renewAll(workerId);
            if (renewed > 0) {
                log.debug("培训评分租约续租 renewed={}", renewed);
            }
        } catch (Exception e) {
            log.warn("培训评分租约续租失败: {}", e.getMessage());
        }
    }

    /** worker 标识：JVM 名（pid@host）+ 随机后缀，重启后旧租约自然到期释放 */
    private static String buildWorkerId() {
        String base = ManagementFactory.getRuntimeMXBean().getName();
        return ("training-" + base + "-" + UUID.randomUUID().toString().substring(0, 8))
                .replaceAll("[^A-Za-z0-9@._-]", "_");
    }
}
