package com.aiextract.scheduler;

import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.service.TrainingDeadlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 截止扫描器 —— 定时找出已逾期的进行中尝试，交给
 * {@link TrainingDeadlineService} 按策略结束。
 *
 * <p>两类来源：</p>
 * <ol>
 *   <li>考试：`training_attempt.deadline`（命中 `idx_tat_deadline`）；</li>
 *   <li>陪练：没有 attempt.deadline，以 `training_assignment.deadline` 判定。</li>
 * </ol>
 *
 * <p>本轮只做调度：查询 + 逐个委托，单条异常不影响整轮。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingDeadlineSweeper {

    private final TrainingAttemptRepository attemptRepository;
    private final TrainingDeadlineService deadlineService;

    @Value("${app.training.deadline.enabled:true}")
    private boolean enabled;

    @Value("${app.training.deadline.batch-size:50}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.training.deadline.interval-ms:60000}",
            initialDelayString = "${app.training.deadline.initial-delay-ms:30000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        // 两类查询各自兜底：考试查询异常不能把陪练逾期当轮也跳过
        try {
            sweep(attemptRepository.findOverdueIds(batchSize));
        } catch (Exception e) {
            log.error("考试逾期扫描查询失败", e);
        }
        try {
            sweep(attemptRepository.findOverduePracticeIds(batchSize));
        } catch (Exception e) {
            log.error("陪练逾期扫描查询失败", e);
        }
    }

    private void sweep(List<UUID> attemptIds) {
        for (UUID attemptId : attemptIds) {
            try {
                deadlineService.handleOverdue(attemptId);
            } catch (Exception e) {
                // 单条失败不能拖垮整轮；下一轮会重新扫到
                log.error("逾期处理失败 attemptId={}", attemptId, e);
            }
        }
    }
}
