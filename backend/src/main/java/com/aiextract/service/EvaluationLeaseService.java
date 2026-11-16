package com.aiextract.service;

import com.aiextract.repository.EvaluationRunItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 评测任务租约服务 —— 领取/续租/释放都在独立事务里完成。
 *
 * <p>为什么单独成一个类：{@code SELECT ... FOR UPDATE SKIP LOCKED} 的行锁必须与随后的
 * {@code UPDATE} 在**同一事务**内提交，否则锁在查询结束后就释放，多实例会重复领取。
 * 放在 Worker 里会出现 Spring 自调用绕过代理、{@code @Transactional} 失效的问题。</p>
 *
 * <p>领取条件同时覆盖 {@code pending} 与 {@code generating}（决策 D5）：
 * 进程崩溃后停在 generating 的行只有靠租约过期被重新领取才能恢复。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationLeaseService {

    private final EvaluationRunItemRepository runItemRepository;

    /** 租约时长（秒）：需覆盖单题「答题 + 评分」的最长耗时，否则会边跑边被他人抢走 */
    @Value("${app.evaluation.lease-seconds:900}")
    private int leaseSeconds;

    /**
     * 领取一批可执行的题目：锁行 → 置 generating → 写租约，同一事务内完成。
     *
     * @return 实际领取到的执行记录 ID（可能为空）
     */
    @Transactional(rollbackFor = Exception.class)
    public List<UUID> claim(UUID runId, String owner, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<UUID> ids = runItemRepository.lockClaimableIds(runId, limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        int claimed = runItemRepository.claimItems(ids, owner, LocalDateTime.now().plusSeconds(leaseSeconds));
        if (claimed != ids.size()) {
            // 行锁应保证两者相等；不等说明条件与锁不一致，属于实现缺陷，必须暴露
            log.warn("领取数量不一致 runId={} locked={} claimed={}", runId, ids.size(), claimed);
        }
        return ids;
    }

    /** 续租本 worker 名下所有在途行；返回续租行数（0 表示当前没有在途任务）。 */
    @Transactional(rollbackFor = Exception.class)
    public int renewAll(String owner) {
        return runItemRepository.renewLeases(owner, LocalDateTime.now().plusSeconds(leaseSeconds));
    }

    /** 释放本 worker 在某批次持有的租约（取消运行/优雅停机）。 */
    @Transactional(rollbackFor = Exception.class)
    public int release(UUID runId, String owner) {
        return runItemRepository.releaseLeases(runId, owner);
    }
}
