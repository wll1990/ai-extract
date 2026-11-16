package com.aiextract.service;

import com.aiextract.config.CompanyScopeService;
import com.aiextract.model.KnowledgeGap;
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
 * AI 培训需求聚合 —— 把「分身答不上来的问题」和「实战没过关的场景」合成能力缺口。
 *
 * <p>只为生成训练服务，不做成绩判定；数据全部按企业隔离（缺口按 space、失败按 assignment）。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-15
 */
@Service
@RequiredArgsConstructor
public class TrainingNeedService {

    private static final String UNCATEGORIZED = "未分类";
    private static final int MAX_SAMPLE_QUERIES = 3;

    private final KnowledgeGapRepository gapRepository;
    private final TrainingTaskRecordRepository recordRepository;
    private final TrainingAccessService accessService;
    private final CompanyScopeService companyScopeService;

    /** 一个能力缺口维度（场景） */
    public record Dimension(String sceneTag, int gapCount, int failedCount, int weight,
                            List<String> sampleQueries, String recommendation) {
    }

    public record NeedsView(int openGaps, int failedTasks, List<Dimension> dimensions) {
    }

    public NeedsView needs(int limit) {
        UUID companyId = accessService.requireCompanyId();
        int max = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));

        Set<UUID> spaceIds = companyScopeService.getSpaceIds(companyId);
        List<KnowledgeGap> gaps = spaceIds.isEmpty() ? List.of()
                : gapRepository.findBySpaceIdInAndStatusOrderByAttemptedQueryCountDesc(
                        spaceIds, "open", PageRequest.of(0, max * 3));
        long openGaps = spaceIds.isEmpty() ? 0
                : gapRepository.countBySpaceIdInAndStatus(spaceIds, "open");

        Map<String, Accumulator> byScene = new LinkedHashMap<>();
        for (KnowledgeGap gap : gaps) {
            String scene = normalize(gap.getSceneTag());
            Accumulator acc = byScene.computeIfAbsent(scene, Accumulator::new);
            acc.gapCount += gap.getAttemptedQueryCount() == null ? 1 : gap.getAttemptedQueryCount();
            if (acc.samples.size() < MAX_SAMPLE_QUERIES
                    && gap.getQuery() != null && !gap.getQuery().isBlank()) {
                acc.samples.add(truncate(gap.getQuery(), 120));
            }
        }
        List<Object[]> failed = recordRepository.countFailedByScene(companyId);
        int failedTotal = 0;
        for (Object[] row : failed) {
            String scene = normalize((String) row[0]);
            int count = ((Number) row[1]).intValue();
            failedTotal += count;
            byScene.computeIfAbsent(scene, Accumulator::new).failedCount += count;
        }

        List<Dimension> dimensions = byScene.values().stream()
                .map(acc -> new Dimension(acc.scene, acc.gapCount, acc.failedCount,
                        acc.gapCount * 2 + acc.failedCount * 3,
                        List.copyOf(acc.samples), recommend(acc)))
                .sorted(Comparator.comparingInt(Dimension::weight).reversed())
                .limit(max)
                .toList();
        return new NeedsView((int) openGaps, failedTotal, dimensions);
    }

    private String recommend(Accumulator acc) {
        if (acc.gapCount > 0 && acc.failedCount > 0) {
            return "既有提问缺口又有实战失败：先补一条知识，再生成场景微练";
        }
        if (acc.gapCount > 0) {
            return "分身回答不了：优先补充知识/案例，再生成微练检验";
        }
        return "实战未通过：生成场景微练，必要时安排补训";
    }

    private String normalize(String sceneTag) {
        return sceneTag == null || sceneTag.isBlank() ? UNCATEGORIZED : sceneTag.trim();
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final class Accumulator {
        private final String scene;
        private int gapCount;
        private int failedCount;
        private final List<String> samples = new ArrayList<>();

        private Accumulator(String scene) {
            this.scene = scene;
        }
    }
}
