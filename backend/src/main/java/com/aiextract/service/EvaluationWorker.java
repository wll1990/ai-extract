package com.aiextract.service;

import com.aiextract.common.AiErrorMapper;
import com.aiextract.config.TokenContext;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.EvaluationRun;
import com.aiextract.model.EvaluationRunItem;
import com.aiextract.model.Skill;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.EvaluationRunRepository;
import com.aiextract.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.UUID;

/**
 * 评测执行器 —— 定时从数据库领取待执行题目，调用分身链路答题并评分。
 *
 * <p>设计约束（docs/38 §9）：任务状态只存 PostgreSQL，不依赖请求线程与内存 @Async；
 * 领取走 {@link EvaluationLeaseService} 的原子更新 + 租约，进程中断后租约到期可被重新领取。</p>
 *
 * <p>并发模型（首版）：{@code @Scheduled(fixedDelay)} 单线程、每次从少量批次里各领 1~2 题，
 * 单次任务耗时有界（不会长时间占住调度线程）。多实例安全由 {@code FOR UPDATE SKIP LOCKED} 保证。
 * 企业级并发上限（计划 §9）尚未实现，部署多实例前需补。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvaluationWorker {

    private static final int ERROR_MESSAGE_MAX_CHARS = 1000;

    private final EvaluationRunRepository runRepository;
    private final EvaluationRunItemRepository runItemRepository;
    private final EvaluationItemRepository itemRepository;
    private final SkillRepository skillRepository;
    private final EvaluationLeaseService leaseService;
    private final EvaluationExecutionStateService stateService;
    private final EvaluationAnswerService answerService;
    private final EvaluationJudgeService judgeService;
    private final RetrievalMetricsService metricsService;
    private final KnowledgeUsageService knowledgeUsageService;
    private final EvaluationRunAggregator aggregator;

    /** 本实例的 worker 标识：进程名 + 随机后缀，用于租约归属与心跳续租 */
    private final String workerId = buildWorkerId();

    @Value("${app.evaluation.worker-enabled:true}")
    private boolean workerEnabled;

    @Value("${app.evaluation.worker-runs-per-tick:5}")
    private int runsPerTick;

    @Value("${app.evaluation.worker-items-per-tick:2}")
    private int itemsPerTick;

    /**
     * 主循环：每次取可推进的批次，各领取并执行少量题目，随后重算计数、判定是否收尾。
     *
     * <p>fixedDelay（而非 fixedRate）保证上一轮结束后才安排下一轮，不会叠加执行。</p>
     */
    @Scheduled(fixedDelayString = "${app.evaluation.worker-interval-ms:5000}",
            initialDelayString = "${app.evaluation.worker-initial-delay-ms:20000}")
    public void tick() {
        if (!workerEnabled) {
            return;
        }
        List<UUID> runIds = runRepository.findActiveRunIds(runsPerTick);
        for (UUID runId : runIds) {
            try {
                processRun(runId);
            } catch (Exception e) {
                // 单个批次异常不能拖垮整轮调度；批次状态由下一轮继续推进
                log.error("评测批次处理失败 runId={}", runId, e);
            }
        }
    }

    /** 心跳：续租本 worker 名下所有在途行，避免长任务被误判为超时。 */
    @Scheduled(fixedDelayString = "${app.evaluation.heartbeat-interval-ms:60000}",
            initialDelayString = "${app.evaluation.heartbeat-initial-delay-ms:30000}")
    public void heartbeat() {
        if (!workerEnabled) {
            return;
        }
        try {
            int renewed = leaseService.renewAll(workerId);
            if (renewed > 0) {
                log.debug("评测租约续租 renewed={}", renewed);
            }
        } catch (Exception e) {
            log.warn("评测租约续租失败: {}", e.getMessage());
        }
    }

    private void processRun(UUID runId) {
        EvaluationRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        if (EvaluationRun.STATUS_CANCEL_REQUESTED.equals(run.getStatus())) {
            stateService.cancel(runId, workerId);
            log.info("评测批次已取消 runId={}", runId);
            return;
        }
        stateService.markRunning(runId);

        List<UUID> claimed = leaseService.claim(runId, workerId, itemsPerTick);
        for (UUID runItemId : claimed) {
            // 答题/评分都会调 LLM；带上批次所属企业
            TokenContext.callWith(null, run.getCompanyId(), () -> executeItem(run, runItemId));
        }
        aggregator.recomputeAndMaybeFinish(runId);
    }

    /** 执行单题：答题 → 评分 → 写回；任何异常记为执行错误，不写假分数。 */
    private void executeItem(EvaluationRun run, UUID runItemId) {
        EvaluationRunItem runItem = runItemRepository.findById(runItemId).orElse(null);
        if (runItem == null) {
            return;
        }
        EvaluationItem item = itemRepository.findById(runItem.getItemId()).orElse(null);
        if (item == null) {
            stateService.markError(runItemId, workerId, "题目不存在: " + runItem.getItemId());
            return;
        }
        Skill skill = skillRepository.findById(run.getSkillId()).orElse(null);
        if (skill == null) {
            stateService.markError(runItemId, workerId, "分身不存在: " + run.getSkillId());
            return;
        }
        try {
            EvaluationAnswerService.AnswerResult answered =
                    answerService.answer(skill, item, run.getCompanyId());
            // 消费回流：评测答题命中的知识/案例也要计入「被引用」，失败不影响评分
            knowledgeUsageService.logEvaluation(run.getCompanyId(), run.getSkillId(),
                    runItemId, answered.hits());
            // 先落「已答 + 证据」，再评分：评分失败时回答与检索证据不丢，错误可归因为「无法判分」
            stateService.markScoring(runItemId, workerId, answered.answer(), answered.evidenceJson());
            EvaluationJudgeService.JudgeOutcome judged =
                    judgeService.judge(runItem, item, answered.answer(), run.getPurpose());
            // 检索指标：金标 vs 实际检索证据，实测命中才计入（不参与 LLM 评分）
            RetrievalMetricsService.Metrics metrics =
                    metricsService.compute(item.getRetrievalExpected(), answered.evidenceJson());
            String ruleCheck = metricsService.attachTo(judged.ruleCheckJson(), metrics);
            stateService.completeItem(runItemId, workerId, judged.verdict(),
                    answered.answer(), answered.evidenceJson(), ruleCheck, judged.aiScoreJson());
        } catch (Exception e) {
            log.error("评测单题执行失败 runId={} runItemId={} itemId={}", run.getId(), runItemId, item.getId(), e);
            stateService.markError(runItemId, workerId, truncateMessage(e));
        }
    }

    private String truncateMessage(Exception e) {
        String message = AiErrorMapper.describe(e);
        return message.length() > ERROR_MESSAGE_MAX_CHARS
                ? message.substring(0, ERROR_MESSAGE_MAX_CHARS) + "…"
                : message;
    }

    /** worker 标识：JVM 名（pid@host）+ 随机后缀，重启后是新标识，旧租约自然到期释放。 */
    private static String buildWorkerId() {
        String base = ManagementFactory.getRuntimeMXBean().getName();
        return ("eval-" + base + "-" + UUID.randomUUID().toString().substring(0, 8))
                .replaceAll("[^A-Za-z0-9@._-]", "_");
    }
}
