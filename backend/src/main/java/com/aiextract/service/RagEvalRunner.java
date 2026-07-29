package com.aiextract.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RAG 评估框架 — 基于 golden dataset 自动度量检索质量。
 *
 * <p>支持的指标: Context Precision@K, Context Recall@K, MRR。
 * 每次 RAG 管线改动后跑一次，量化改动效果。
 *
 * @author AI Extract Team
 * @since 2026-07-30
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvalRunner {

    private final GrainRetriever grainRetriever;
    private final RagPipelineService ragPipelineService;

    /**
     * 单条评估结果。
     */
    public record EvalResult(String query, double contextPrecision, double contextRecall,
                              double mrr, int retrievedCount, double avgSimilarity) {}

    /**
     * 批量评估摘要。
     */
    public record EvalSummary(List<EvalResult> results, double avgPrecision, double avgRecall,
                               double avgMrr, int totalQueries) {}

    /**
     * 对 golden dataset 中的每条 query 执行完整 RAG 检索并计算指标。
     *
     * @param queries      测试 query 列表
     * @param spaceId      空间 ID
     * @param expectedTags 每条 query 期望匹配的场景标签集合
     * @param topK         检索返回数
     * @return 评估摘要
     */
    public EvalSummary evaluate(List<String> queries, UUID spaceId,
                                 List<Set<String>> expectedTags, int topK) {
        List<EvalResult> results = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            String query = queries.get(i);
            Set<String> expected = i < expectedTags.size() ? expectedTags.get(i) : Set.of();
            EvalResult r = evaluateOne(query, spaceId, expected, topK);
            results.add(r);
        }
        double avgP = results.stream().mapToDouble(r -> r.contextPrecision).average().orElse(0);
        double avgR = results.stream().mapToDouble(r -> r.contextRecall).average().orElse(0);
        double avgM = results.stream().mapToDouble(r -> r.mrr).average().orElse(0);
        log.info("RAG评估完成 queries={} avgPrecision@{}={:.3f} avgRecall={:.3f} avgMRR={:.3f}",
                results.size(), topK, avgP, avgR, avgM);
        return new EvalSummary(results, avgP, avgR, avgM, results.size());
    }

    private EvalResult evaluateOne(String query, UUID spaceId, Set<String> expectedTags, int topK) {
        List<GrainRetriever.GrainResult> grains = grainRetriever.retrieveWithScores(query, spaceId, topK);
        double avgSim = grains.stream().mapToDouble(r -> r.similarity()).average().orElse(0);

        // Context Precision: 检索结果中相关标签的占比
        long relevantCount = grains.stream()
                .filter(r -> {
                    String tag = r.grain().getSceneTag();
                    return tag != null && expectedTags.stream().anyMatch(tag::contains);
                }).count();
        double precision = grains.isEmpty() ? 0 : (double) relevantCount / grains.size();

        // Context Recall: 期望标签中有多少被检索到
        Set<String> retrievedTags = new HashSet<>();
        grains.forEach(r -> {
            if (r.grain().getSceneTag() != null) retrievedTags.add(r.grain().getSceneTag());
        });
        long matchedExpected = expectedTags.stream()
                .filter(et -> retrievedTags.stream().anyMatch(rt -> rt.contains(et) || et.contains(rt)))
                .count();
        double recall = expectedTags.isEmpty() ? 1.0 : (double) matchedExpected / expectedTags.size();

        // MRR: 第一个相关结果的倒数排名
        double mrr = 0;
        for (int i = 0; i < grains.size(); i++) {
            String tag = grains.get(i).grain().getSceneTag();
            if (tag != null && expectedTags.stream().anyMatch(tag::contains)) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }

        return new EvalResult(query, precision, recall, mrr, grains.size(), avgSim);
    }
}
