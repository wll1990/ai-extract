package com.aiextract.service;

import com.aiextract.model.EvaluationRun;
import com.aiextract.model.EvaluationRunItem;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.EvaluationRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评测批次聚合器 —— 计数与收尾判定的唯一实现。
 *
 * <p>Worker（自动执行）与 ReviewService（人工复核后重聚合）必须用同一套口径，
 * 否则复核后看板和报告会对不上。计数只统计每题**最新一次尝试**，重试不重复计数。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationRunAggregator {

    private final EvaluationRunRepository runRepository;
    private final EvaluationRunItemRepository runItemRepository;

    /**
     * 重算批次计数器与平均分；全部题目到达终态时收尾。
     *
     * @return true 表示本次调用使批次进入终态
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean recomputeAndMaybeFinish(UUID runId) {
        Map<String, Integer> statusCounts = toCountMap(runItemRepository.countLatestStatusByRun(runId));
        Map<String, Integer> verdictCounts = toCountMap(runItemRepository.countLatestVerdictByRun(runId));

        int total = sum(statusCounts);
        int completed = count(statusCounts, EvaluationRunItem.STATUS_COMPLETED);
        int error = count(statusCounts, EvaluationRunItem.STATUS_ERROR);
        int executing = count(statusCounts, EvaluationRunItem.STATUS_PENDING)
                + count(statusCounts, EvaluationRunItem.STATUS_GENERATING)
                + count(statusCounts, EvaluationRunItem.STATUS_SCORING);
        int passed = count(verdictCounts, EvaluationRunItem.VERDICT_PASS);
        int failed = count(verdictCounts, EvaluationRunItem.VERDICT_FAIL);
        int undetermined = count(verdictCounts, EvaluationRunItem.VERDICT_UNDETERMINED);

        Double avg = runItemRepository.avgTotalScoreByRun(runId);
        BigDecimal scoreAvg = avg == null ? null : BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP);

        LocalDateTime now = LocalDateTime.now();
        runRepository.updateCounters(runId, total, completed, error, passed, failed, undetermined, scoreAvg, now);

        if (executing > 0) {
            return false;
        }
        String status;
        if (total == 0) {
            // 没有题目可执行（异常数据）：直接失败收尾，避免空批次永远停留在 running
            status = EvaluationRun.STATUS_FAILED;
        } else if (error == 0) {
            status = EvaluationRun.STATUS_COMPLETED;
        } else {
            status = completed > 0 ? EvaluationRun.STATUS_PARTIAL_FAILED : EvaluationRun.STATUS_FAILED;
        }
        String reviewStatus = completed > 0
                ? EvaluationRun.REVIEW_PENDING
                : EvaluationRun.REVIEW_NOT_READY;
        int finished = runRepository.finish(runId, status, reviewStatus, now, now);
        if (finished > 0) {
            log.info("评测批次收尾 runId={} status={} reviewStatus={} total={} completed={} error={} avg={}",
                    runId, status, reviewStatus, total, completed, error, scoreAvg);
            return true;
        }
        return false;
    }

    private Map<String, Integer> toCountMap(List<Object[]> rows) {
        Map<String, Integer> map = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) {
                continue;
            }
            map.merge(row[0].toString(), ((Number) row[1]).intValue(), Integer::sum);
        }
        return map;
    }

    private int count(Map<String, Integer> counts, String key) {
        return counts.getOrDefault(key, 0);
    }

    private int sum(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
