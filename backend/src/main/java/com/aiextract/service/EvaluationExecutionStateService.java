package com.aiextract.service;

import com.aiextract.model.EvaluationRun;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.EvaluationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 评测执行态写入服务 —— Worker 的每一次状态落库都在**独立的短事务**里完成。
 *
 * <p>为什么必须单独成类：{@code @Modifying} 查询要求有事务；而 Worker 的
 * {@code executeItem} 里包着答题/评分两次 AI 调用，绝对不能整体包进事务
 * （事务内禁止 AI/HTTP 调用，且会长时间占用连接）。因此把「写库」与「调模型」拆开：
 * 每个状态迁移一个短事务，AI 调用留在事务外。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationExecutionStateService {

    private final EvaluationRunRepository runRepository;
    private final EvaluationRunItemRepository runItemRepository;
    private final EvaluationLeaseService leaseService;

    /** 批次进入执行态（queue→running），记录首次开始时间。 */
    @Transactional(rollbackFor = Exception.class)
    public int markRunning(UUID runId) {
        return runRepository.markRunning(runId);
    }

    /**
     * 取消收尾：释放租约 → 未执行的题标记 cancelled → 批次置 cancelled。
     * 标记这一步是为了防止「取消后重试」把从未执行的 pending 题重新跑起来。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(UUID runId, String owner) {
        leaseService.release(runId, owner);
        runItemRepository.markCancelledByRun(runId);
        runRepository.markCancelled(runId);
    }

    /** 答题完成、进入评分态（先把回答与检索证据落库，评分失败也不丢）。 */
    @Transactional(rollbackFor = Exception.class)
    public int markScoring(UUID runItemId, String owner, String answer, String evidence) {
        return runItemRepository.markScoring(runItemId, owner, answer, evidence);
    }

    /** 写回最终结果并释放租约。 */
    @Transactional(rollbackFor = Exception.class)
    public int completeItem(UUID runItemId, String owner, String verdict, String answer,
                            String evidence, String ruleCheck, String score) {
        return runItemRepository.completeItem(runItemId, owner, verdict, answer, evidence, ruleCheck, score);
    }

    /** 记为执行错误并释放租约（执行态 error，不写业务结论）。 */
    @Transactional(rollbackFor = Exception.class)
    public int markError(UUID runItemId, String owner, String message) {
        return runItemRepository.markError(runItemId, owner, message);
    }

    /** 读取批次（Worker 每轮刷新用，避免拿到旧状态）。 */
    public EvaluationRun findRun(UUID runId) {
        return runRepository.findById(runId).orElse(null);
    }
}
