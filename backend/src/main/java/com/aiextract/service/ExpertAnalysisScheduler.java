package com.aiextract.service;

import com.aiextract.model.ExpertSkill;
import com.aiextract.repository.ExpertSkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;


/**
 * 萃取师材料异步分析调度器
 *
 * <p>采用 DB 层任务队列 + 乐观锁模式，+ redis 替代 @Async 方案。
 * 每 30 秒扫描 pending 状态且未锁定的任务，
 * 通过 UPDATE WHERE locked_by IS NULL 抢占，
 * 保证多实例下仅一个 worker 处理同一任务。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-30
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpertAnalysisScheduler {

    private final ExpertSkillRepository expertSkillRepository;
    private final ExpertService expertService;

    @Autowired
    @Lazy
    private ExpertAnalysisScheduler self;

    /** 锁超时时间（分钟）：超过此时间的锁视为失效 */
    private static final int LOCK_TIMEOUT_MINUTES = 5;

    /** worker 标识：hostname + 线程名 */
    private final String workerId = initWorkerId();

    private String initWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + Thread.currentThread().getId();
        } catch (Exception e) {
            return "worker-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    /**
     * 定时扫描并处理待分析任务
     *
     * <p>每 30 秒执行一次，先查待处理列表，逐条尝试乐观锁抢占，
     * 抢占成功后执行分析并更新状态，最后释放锁。</p>
     *
     * <p>锁操作（tryLock/releaseLock）使用独立短事务（REQUIRES_NEW），
     * 确保锁状态立即对其他实例可见。分析任务在 ExpertService 自有事务中执行，
     * 不持有本方法的事务。</p>
     */
    @Scheduled(fixedDelay = 30_000)
    public void scanAndProcess() {
        LocalDateTime timeout = LocalDateTime.now().minusMinutes(LOCK_TIMEOUT_MINUTES);
        List<ExpertSkill> pendingTasks = expertSkillRepository.findPendingTasks(timeout);

        if (pendingTasks.isEmpty()) { return; }

        log.debug("发现 {} 个待分析任务", pendingTasks.size());

        for (ExpertSkill task : pendingTasks) {
            UUID taskId = task.getId();

            // 乐观锁抢占（独立短事务，立即提交）
            if (!self.tryLock(taskId)) { continue; }

            log.info("Worker[{}] 抢到任务, expertId: {}, name: {}", workerId, taskId, task.getName());

            try {
                // 分析任务在 ExpertService.analyzeMaterials 自有事务中执行
                expertService.analyzeMaterials(taskId);
            } catch (Exception e) {
                log.error("任务分析失败, expertId: {}", taskId, e);
                // 失败重置为 pending，调度器30s后重新拾取（解析失败doc已标failed，不会被重复解析）
                self.resetToPending(taskId);
            } finally {
                // 释放锁（独立短事务，立即提交）
                self.releaseLock(taskId);
            }
        }
    }

    /** 乐观锁抢占（独立短事务，确保多实例可见） */
    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean tryLock(UUID taskId) {
        return expertSkillRepository.tryLock(taskId, workerId) > 0;
    }

    /** 释放锁（独立短事务，确保多实例可见） */
    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void releaseLock(UUID taskId) {
        expertSkillRepository.releaseLock(taskId);
    }

    /** 失败回退为 pending（短事务），调度器 30s 后重新拾取 */
    @Transactional(rollbackFor = Exception.class, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void resetToPending(UUID taskId) {
        expertSkillRepository.findById(taskId).ifPresent(e -> {
            e.setStatus("pending");
            expertSkillRepository.save(e);
        });
    }
}
