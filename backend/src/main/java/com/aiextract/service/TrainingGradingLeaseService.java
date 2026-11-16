package com.aiextract.service;

import com.aiextract.repository.TrainingAttemptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 培训评分租约服务 —— 领取/续租都在独立事务内完成。
 *
 * <p>{@code SELECT ... FOR UPDATE SKIP LOCKED} 的行锁必须与随后 {@code UPDATE} 同一事务提交，
 * 否则锁在查询结束即释放，多实例会重复评分。放在 Worker 里会被 Spring 自调用绕过代理。</p>
 *
 * <p>领取条件覆盖 {@code submitted} 与 {@code grading}（后者指租约已过期），
 * 保证进程崩溃后停在中途的行能被重新领取。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingGradingLeaseService {

    private final TrainingAttemptRepository attemptRepository;

    /** 租约时长（秒）：需覆盖一次「考试多题判分」的最长耗时 */
    @Value("${app.training.grading.lease-seconds:900}")
    private int leaseSeconds;

    /**
     * 领取一批可评分的尝试：锁行 → 置 grading + 写租约，同一事务内完成。
     *
     * @return 实际领取到的尝试 ID（可能为空）
     */
    @Transactional(rollbackFor = Exception.class)
    public List<UUID> claim(String owner, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<UUID> ids = attemptRepository.lockClaimableIds(limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        int claimed = attemptRepository.claim(ids, owner, leaseSeconds);
        if (claimed != ids.size()) {
            // 行锁应保证两者相等；不等说明领取条件与锁不一致，属实现缺陷，必须暴露
            log.warn("培训评分领取数量不一致 locked={} claimed={}", ids.size(), claimed);
        }
        return ids;
    }

    /** 续租本 worker 名下所有在途尝试，避免长任务被误判超时 */
    @Transactional(rollbackFor = Exception.class)
    public int renewAll(String owner) {
        return attemptRepository.renewLeases(owner, leaseSeconds);
    }
}
