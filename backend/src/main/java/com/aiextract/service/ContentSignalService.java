package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.model.KnowledgeGap;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.repository.KnowledgeGapRepository;
import com.aiextract.repository.TrainingTaskRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 内容需求聚合 —— 知识/案例/考题生成的统一信号源。
 *
 * <p>信号来自真实失败：分身答不上来的问题、实战未通过场景、评测失败题。
 * 只聚合，不生成；生成由 {@link ContentGenerationService} 负责。</p>
 */
@Service
@RequiredArgsConstructor
public class ContentSignalService {

    private static final String UNCATEGORIZED = "未分类";

    private final KnowledgeGapRepository gapRepository;
    private final TrainingTaskRecordRepository recordRepository;
    private final EvaluationRunItemRepository runItemRepository;
    private final TrainingAccessService accessService;
    private final CompanyScopeService companyScopeService;

    /** 单个信号（保留来源 ref，生成草稿时作为证据引用） */
    public record Signal(String type, String sceneTag, String text, String ref) {
    }

    /** 一个场景下的内容需求 */
    public record Need(String sceneTag, int gapCount, int trainingFailCount, int evalFailCount,
                       int weight, List<String> samples, String recommendation) {
    }

    public record NeedsView(int gaps, int trainingFailures, int evalFailures, List<Need> needs) {
    }

    /** 最近 N 条失败信号（生成单条草稿时用） */
    public List<Signal> recentSignals(int limit) {
        UUID companyId = accessService.requireCompanyId();
        int max = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        List<Signal> signals = new ArrayList<>();

        Set<UUID> spaceIds = companyScopeService.getSpaceIds(companyId);
        if (!spaceIds.isEmpty()) {
            for (KnowledgeGap gap : gapRepository
                    .findBySpaceIdInAndStatusOrderByAttemptedQueryCountDesc(
                            spaceIds, "open", PageRequest.of(0, max))) {
                signals.add(new Signal("knowledge_gap", normalize(gap.getSceneTag()),
                        gap.getQuery(), gap.getId().toString()));
            }
        }
        for (Object[] row : recordRepository.countFailedByScene(companyId)) {
            String scene = normalize((String) row[0]);
            signals.add(new Signal("training_failure", scene,
                    "实战场景未通过：" + scene, scene));
        }
        for (Object[] row : runItemRepository.findRecentFailedItems(companyId, max)) {
            String question = String.valueOf(row[1]);
            String scene = normalize(row[2] == null ? null : String.valueOf(row[2]));
            signals.add(new Signal("eval_issue", scene, question, String.valueOf(row[0])));
        }
        return signals;
    }

    public NeedsView needs(int limit) {
        UUID companyId = accessService.requireCompanyId();
        int max = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));

        Set<UUID> spaceIds = companyScopeService.getSpaceIds(companyId);
        List<KnowledgeGap> gaps = spaceIds.isEmpty() ? List.of()
                : gapRepository.findBySpaceIdInAndStatusOrderByAttemptedQueryCountDesc(
                        spaceIds, "open", PageRequest.of(0, max * 3));
        List<Object[]> training = recordRepository.countFailedByScene(companyId);
        List<Object[]> eval = runItemRepository.findRecentFailedItems(companyId, max * 2);

        Map<String, Acc> byScene = new LinkedHashMap<>();
        for (KnowledgeGap gap : gaps) {
            Acc acc = byScene.computeIfAbsent(normalize(gap.getSceneTag()), Acc::new);
            acc.gapCount += gap.getAttemptedQueryCount() == null ? 1 : gap.getAttemptedQueryCount();
            addSample(acc, gap.getQuery());
        }
        for (Object[] row : training) {
            Acc acc = byScene.computeIfAbsent(normalize((String) row[0]), Acc::new);
            acc.trainingFailCount += ((Number) row[1]).intValue();
        }
        for (Object[] row : eval) {
            Acc acc = byScene.computeIfAbsent(normalize(row[2] == null ? null : String.valueOf(row[2])),
                    Acc::new);
            acc.evalFailCount++;
            addSample(acc, String.valueOf(row[1]));
        }

        List<Need> needs = byScene.values().stream()
                .map(acc -> new Need(acc.scene, acc.gapCount, acc.trainingFailCount,
                        acc.evalFailCount, acc.weight(), List.copyOf(acc.samples),
                        recommend(acc)))
                .sorted(Comparator.comparingInt(Need::weight).reversed())
                .limit(max)
                .toList();
        return new NeedsView(gaps.size(), training.size(), eval.size(), needs);
    }

    private void addSample(Acc acc, String text) {
        if (text == null || text.isBlank() || acc.samples.size() >= 5) {
            return;
        }
        String value = text.trim();
        if (!acc.samples.contains(value)) {
            acc.samples.add(value.length() > 160 ? value.substring(0, 160) : value);
        }
    }

    private String recommend(Acc acc) {
        boolean hasEval = acc.evalFailCount > 0;
        boolean hasTrain = acc.trainingFailCount > 0;
        boolean hasGap = acc.gapCount > 0;
        if (hasEval && (hasGap || hasTrain)) {
            return "分身回答与实战都不稳：补知识并用评测回归验证";
        }
        if (hasEval) {
            return "评测失败：先补知识与金标，再重跑回归题";
        }
        if (hasTrain) {
            return "实战未通过：补案例/知识，并生成微练";
        }
        return "分身答不上来：优先补一条可检索知识";
    }

    private String normalize(String sceneTag) {
        return sceneTag == null || sceneTag.isBlank() ? UNCATEGORIZED : sceneTag.trim();
    }

    private static final class Acc {
        private final String scene;
        private int gapCount;
        private int trainingFailCount;
        private int evalFailCount;
        private final List<String> samples = new ArrayList<>();

        private Acc(String scene) {
            this.scene = scene;
        }

        private int weight() {
            return gapCount * 2 + trainingFailCount * 3 + evalFailCount * 4;
        }
    }
}
