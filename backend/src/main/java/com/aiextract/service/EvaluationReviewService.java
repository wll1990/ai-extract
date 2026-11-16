package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.EvaluationReviewRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationReview;
import com.aiextract.model.EvaluationRun;
import com.aiextract.model.EvaluationRunItem;
import com.aiextract.repository.EvaluationReviewRepository;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.EvaluationRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 评测人工复核服务 —— 追加式记录，AI 原分不可覆盖。
 *
 * <p>三种动作的语义严格区分：</p>
 * <ul>
 *   <li>{@code adjust}：人工改分，必须带 {@code adjustedScore.total_score}，结论按新分与及格线重算</li>
 *   <li>{@code undetermined}：人工判定无法判断，结论置为 undetermined</li>
 *   <li>{@code confirm}：确认 AI 结论，结论不变，仅留痕</li>
 * </ul>
 *
 * <p>复核后重新聚合批次计数，并把审核态拉回 {@code pending_review}（已定稿的批次需重新定稿，
 * 报告按版本追加，不覆盖历史）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationReviewService {

    private final EvaluationRunItemRepository runItemRepository;
    private final EvaluationRunRepository runRepository;
    private final EvaluationReviewRepository reviewRepository;
    private final CompanyScopeService companyScopeService;
    private final EvaluationRunAggregator aggregator;
    private final ObjectMapper objectMapper;

    @Value("${app.evaluation.pass-threshold:60}")
    private int passThreshold;

    @Transactional(rollbackFor = Exception.class)
    public EvaluationReview review(UUID runItemId, EvaluationReviewRequest req) {
        EvaluationRunItem runItem = runItemRepository.findById(runItemId)
                .orElseThrow(() -> new BusinessException(404, "执行记录不存在"));
        companyScopeService.assertRunReadable(runItem.getRunId());
        if (!EvaluationReview.isValidAction(req.getAction())) {
            throw new BusinessException(400, "非法复核动作: " + req.getAction());
        }
        if (!EvaluationRunItem.STATUS_COMPLETED.equals(runItem.getStatus())) {
            throw new BusinessException(409, "只能复核执行完成的题目，当前执行态=" + runItem.getStatus());
        }

        String action = req.getAction();
        String adjustedScore = null;
        String newVerdict;
        if (EvaluationReview.ACTION_ADJUST.equals(action)) {
            adjustedScore = requireAdjustedScore(req.getAdjustedScore());
            int total = readTotalScore(adjustedScore);
            newVerdict = total >= passThreshold
                    ? EvaluationRunItem.VERDICT_PASS
                    : EvaluationRunItem.VERDICT_FAIL;
        } else if (EvaluationReview.ACTION_UNDETERMINED.equals(action)) {
            newVerdict = EvaluationRunItem.VERDICT_UNDETERMINED;
        } else {
            newVerdict = runItem.getVerdict();
        }

        LocalDateTime now = LocalDateTime.now();
        EvaluationReview review = EvaluationReview.builder()
                .id(UUID.randomUUID())
                .runItemId(runItemId)
                .reviewerId(TokenContext.get())
                .action(action)
                .reason(req.getReason().trim())
                .originalScore(runItem.getAiScore())
                .adjustedScore(adjustedScore)
                .createdAt(now)
                .build();
        reviewRepository.save(review);

        runItemRepository.updateVerdict(runItemId, newVerdict, now);
        aggregator.recomputeAndMaybeFinish(runItem.getRunId());
        runRepository.updateReviewStatus(runItem.getRunId(), EvaluationRun.REVIEW_PENDING, now);
        log.info("评测复核 runItemId={} action={} verdict={} reviewer={}",
                runItemId, action, newVerdict, review.getReviewerId());
        return review;
    }

    /** 改分必须带合法 total_score（0-100）；不合法直接拒绝，不静默取默认值。 */
    private String requireAdjustedScore(String adjustedScore) {
        if (adjustedScore == null || adjustedScore.isBlank()) {
            throw new BusinessException(400, "改分必须提供 adjustedScore");
        }
        int total = readTotalScore(adjustedScore);
        if (total < 0 || total > 100) {
            throw new BusinessException(400, "adjustedScore.total_score 必须在 0-100 之间");
        }
        return adjustedScore;
    }

    private int readTotalScore(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.has("total_score")) {
                throw new BusinessException(400, "adjustedScore 缺少 total_score");
            }
            return node.get("total_score").asInt();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400, "adjustedScore 不是合法 JSON");
        }
    }
}
