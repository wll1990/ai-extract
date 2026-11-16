package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingCohort;
import com.aiextract.repository.KnowledgeGapRepository;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingCertificationRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 培训效果视图 —— 回答「这轮培训到底有没有用」，而不是只看完成率。
 *
 * <p>指标口径：</p>
 * <ul>
 *   <li>完成率 = 已认证 / 未取消分配；</li>
 *   <li>AI 转人工率 = 待复核+评分失败 / 已出结论尝试；用于判断 AI 评分是否可信；</li>
 *   <li>场景均分 = 已出结论尝试的 AI 原始分；</li>
 *   <li>待补缺口 = 企业当前 open 知识缺口，作为「还没练到的地方」。</li>
 * </ul>
 *
 * @author AI Extract Team
 * @since 2026-11-15
 */
@Service
@RequiredArgsConstructor
public class TrainingEffectService {

    private final TrainingAssignmentRepository assignmentRepository;
    private final TrainingAttemptRepository attemptRepository;
    private final TrainingTaskRecordRepository recordRepository;
    private final TrainingCertificationRepository certificationRepository;
    private final KnowledgeGapRepository gapRepository;
    private final TrainingAccessService accessService;
    private final CompanyScopeService companyScopeService;

    public record SceneScore(String sceneTag, double avgScore, long attempts) {
    }

    public record EffectView(UUID cohortId, String cohortName, int totalLearners,
                             int certified, int cancelled, int pendingCertification,
                             double certificationRate, long remediationRounds,
                             long gradedAttempts, long needReviewAttempts,
                             double reviewRate, List<SceneScore> scenes,
                             int openGaps, String summary) {
    }

    @Transactional(readOnly = true)
    public EffectView effect(UUID cohortId) {
        TrainingCohort cohort = accessService.assertCanReview(cohortId);
        List<TrainingAssignment> assignments =
                assignmentRepository.findByCohortIdOrderByCreatedAtAsc(cohortId);

        int total = assignments.size();
        int cancelled = 0;
        int pendingCertification = 0;
        List<UUID> ids = new ArrayList<>(total);
        for (TrainingAssignment a : assignments) {
            ids.add(a.getId());
            if (TrainingAssignment.STATUS_CANCELLED.equals(a.getStatus())) {
                cancelled++;
            }
            if (TrainingAssignment.STATUS_PENDING_CERTIFICATION.equals(a.getStatus())) {
                pendingCertification++;
            }
        }
        int certified = ids.isEmpty() ? 0
                : certificationRepository.findByAssignmentIdInAndStatus(ids, "confirmed").size();
        int effective = Math.max(0, total - cancelled);
        double certificationRate = effective == 0 ? 0 : round1(certified * 100.0 / effective);

        long remediationRounds = recordRepository.countRemediationRoundsByCohort(cohortId);

        Map<String, Long> statusCounts = new LinkedHashMap<>();
        for (Object[] row : attemptRepository.countByCohortGroupByStatus(cohortId)) {
            statusCounts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        long graded = 0;
        long needReview = 0;
        for (Map.Entry<String, Long> e : statusCounts.entrySet()) {
            switch (e.getKey()) {
                case "finalized" -> graded += e.getValue();
                case "pending_review", "error" -> {
                    graded += e.getValue();
                    needReview += e.getValue();
                }
                default -> { }
            }
        }
        double reviewRate = graded == 0 ? 0 : round1(needReview * 100.0 / graded);

        List<SceneScore> scenes = new ArrayList<>();
        for (Object[] row : attemptRepository.avgScoreByScene(cohortId)) {
            scenes.add(new SceneScore(String.valueOf(row[0]),
                    ((Number) row[1]).doubleValue(), ((Number) row[2]).longValue()));
        }

        int openGaps = 0;
        UUID companyId = cohort.getCompanyId();
        var spaceIds = companyScopeService.getSpaceIds(companyId);
        if (!spaceIds.isEmpty()) {
            openGaps = (int) gapRepository.countBySpaceIdInAndStatus(spaceIds, "open");
        }

        return new EffectView(cohortId, cohort.getName(), total, certified, cancelled,
                pendingCertification, certificationRate, remediationRounds, graded,
                needReview, reviewRate, scenes, openGaps, summary(certificationRate,
                remediationRounds, reviewRate, openGaps, scenes));
    }

    private String summary(double certificationRate, long remediationRounds, double reviewRate,
                           int openGaps, List<SceneScore> scenes) {
        StringBuilder sb = new StringBuilder();
        sb.append("认证率 ").append(certificationRate).append("%");
        if (remediationRounds > 0) {
            sb.append("；发生补训 ").append(remediationRounds).append(" 轮");
        }
        if (reviewRate > 0) {
            sb.append("；AI 转人工 ").append(reviewRate).append("%（用于校准评分标准）");
        }
        if (!scenes.isEmpty()) {
            SceneScore lowest = scenes.stream()
                    .min((a, b) -> Double.compare(a.avgScore(), b.avgScore())).orElse(null);
            if (lowest != null) {
                sb.append("；最薄弱场景「").append(lowest.sceneTag())
                        .append("」均分 ").append(lowest.avgScore());
            }
        }
        if (openGaps > 0) {
            sb.append("；仍有 ").append(openGaps).append(" 个待补知识缺口");
        }
        return sb.toString();
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
