package com.aiextract.service;

import com.aiextract.model.ExperienceGrain;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 语义检索 top-K 经验颗粒
 *
 * 使用 pgvector cosine_distance 算子 (<=>) 按语义相似度排序，
 * 结合 weight 字段做综合打分: relevance = (1 - cosine_distance) * weight
  * @author AI Extract Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrainRetriever {
    private final JdbcTemplate jdbc;
    private final DashScopeEmbeddingService embeddingService;

    /**
     * 语义检索 — 两步走：先 ANN 命中 HNSW 索引，再 Java 端 weight 重排
     */
    public List<ExperienceGrain> retrieve(String question, UUID spaceId, int topK) {
        return retrieveWithScores(question, spaceId, topK).stream()
                .map(r -> r.grain).collect(java.util.stream.Collectors.toList());
    }

    /** 带相似度分数的语义检索，用于分层标记颗粒质量 */
    public List<GrainResult> retrieveWithScores(String question, UUID spaceId, int topK) {
        long t0 = System.currentTimeMillis();
        float[] queryVector = embeddingService.embed(question);
        if (queryVector == null || queryVector.length == 0) {
            log.error("嵌入服务返回空 question={}", question.substring(0, Math.min(100, question.length())));
            return List.of();
        }
        PGvector queryVec = new PGvector(queryVector);

        // Step 1: ANN 检索 + 记录余弦相似度
        Map<UUID, Double> simMap = new LinkedHashMap<>();
        List<ExperienceGrain> candidates = jdbc.query("""
            SELECT g.*, 1.0 - (g.embedding <=> ?) AS similarity
            FROM experience_grain g
            WHERE g.space_id = ? AND g.status = 'active' AND g.embedding IS NOT NULL
            AND (g.quality_score IS NULL OR g.quality_score >= 3.0)
            ORDER BY g.embedding <=> ?
            LIMIT ?
            """,
            (rs, rowNum) -> {
                ExperienceGrain g = mapGrainRow(rs, rowNum);
                simMap.put(g.getId(), rs.getDouble("similarity"));
                return g;
            },
            queryVec, spaceId, queryVec, topK * 3
        );

        // Step 2: weight 重排 → 组装结果
        List<GrainResult> results = candidates.stream()
                .sorted((a, b) -> Double.compare(
                        simMap.getOrDefault(b.getId(), 0.0) * getWeight(b),
                        simMap.getOrDefault(a.getId(), 0.0) * getWeight(a)))
                .limit(topK)
                .map(g -> new GrainResult(g, round2(simMap.getOrDefault(g.getId(), 0.0)),
                        round2(simMap.getOrDefault(g.getId(), 0.0) * getWeight(g))))
                .collect(java.util.stream.Collectors.toList());

        log.info("RAG检索完成 {}ms candidates={} results={} scores={}",
                System.currentTimeMillis() - t0, candidates.size(), results.size(),
                results.stream().map(r -> String.format("%.2f", r.similarity)).collect(java.util.stream.Collectors.toList()));
        return results;
    }

    /**
     * 多空间语义检索 — 跨多个 space 做 pgvector ANN + weight 重排。
     *
     * <p>逐 space 执行 ANN 检索（每个 topK×3），Java 端合并去重后全局 weight 重排。
     * 避免单条 WHERE space_id IN (...) 在大 space 数量下的 Bitmap Index Scan 退化。</p>
     *
     * @param question 查询文本
     * @param spaceIds 空间 ID 列表（非空）
     * @param topK     最大返回数
     * @return 按 weightedScore 降序排列的结果
     */
    public List<GrainResult> retrieveWithScores(String question, List<UUID> spaceIds, int topK) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }
        long t0 = System.currentTimeMillis();
        float[] queryVector = embeddingService.embed(question);
        if (queryVector == null || queryVector.length == 0) {
            log.error("嵌入服务返回空 question={}", question.substring(0, Math.min(100, question.length())));
            return List.of();
        }
        PGvector queryVec = new PGvector(queryVector);

        // ── Step 1: 多空间并行 ANN 检索（P1-5） ──
        Map<UUID, Double> simMap = new java.util.concurrent.ConcurrentHashMap<>();
        List<ExperienceGrain> candidates = java.util.Collections.synchronizedList(new ArrayList<>());

        List<java.util.concurrent.CompletableFuture<Void>> futures = spaceIds.stream()
            .map(spaceId -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                List<ExperienceGrain> spaceResults = jdbc.query("""
                    SELECT g.*, 1.0 - (g.embedding <=> ?) AS similarity
                    FROM experience_grain g
                    WHERE g.space_id = ? AND g.status = 'active' AND g.embedding IS NOT NULL
                    AND (g.quality_score IS NULL OR g.quality_score >= 3.0)
                    ORDER BY g.embedding <=> ?
                    LIMIT ?
                    """,
                    (rs, rowNum) -> {
                        ExperienceGrain g = mapGrainRow(rs, rowNum);
                        double sim = rs.getDouble("similarity");
                        simMap.merge(g.getId(), sim, Math::max);
                        return g;
                    },
                    queryVec, spaceId, queryVec, topK * 3
                );
                candidates.addAll(spaceResults);
            }))
            .toList();

        // 等待所有空间查询完成
        java.util.concurrent.CompletableFuture.allOf(
            futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();

        // ── Step 2: 去重 + weight 全局重排 ──
        List<GrainResult> results = candidates.stream()
                .collect(Collectors.toMap(
                        ExperienceGrain::getId,
                        g -> g,
                        (a, b) -> a))
                .values().stream()
                .sorted((a, b) -> Double.compare(
                        simMap.getOrDefault(b.getId(), 0.0) * getWeight(b),
                        simMap.getOrDefault(a.getId(), 0.0) * getWeight(a)))
                .limit(topK)
                .map(g -> new GrainResult(g,
                        round2(simMap.getOrDefault(g.getId(), 0.0)),
                        round2(simMap.getOrDefault(g.getId(), 0.0) * getWeight(g))))
                .collect(Collectors.toList());

        log.info("多空间RAG检索完成 {}ms spaces={} candidates={} results={} scores={}",
                System.currentTimeMillis() - t0, spaceIds.size(), candidates.size(),
                results.size(),
                results.stream().map(r -> String.format("%.2f", r.similarity)).collect(Collectors.toList()));
        return results;
    }

    // ============================================================
    // Hybrid Search: Dense + Sparse → RRF 融合
    // ============================================================

    /** RRF 融合常量 — 行业标准值，reward 两边都排前列的文档 */
    private static final int RRF_K = 60;

    /**
     * BM25 近似全文检索 — PostgreSQL ts_rank。
     * 对产品名、编号、术语等精确匹配效果远超向量搜索。
     */
    public List<GrainResult> bm25Search(String query, UUID spaceId, int topK) {
        List<GrainResult> results = jdbc.query("""
            SELECT g.*, ts_rank(g.search_text, plainto_tsquery('simple', ?)) AS bm25_score
            FROM experience_grain g
            WHERE g.space_id = ? AND g.status = 'active' AND g.embedding IS NOT NULL
              AND g.search_text @@ plainto_tsquery('simple', ?)
            ORDER BY bm25_score DESC
            LIMIT ?
            """,
            (rs, rowNum) -> {
                ExperienceGrain g = mapGrainRow(rs, rowNum);
                double score = rs.getDouble("bm25_score");
                return new GrainResult(g, round2(score), round2(score * getWeight(g)));
            },
            query, spaceId, query, topK * 3
        );
        return results;
    }

    /**
     * Hybrid Search — Dense(向量) + Sparse(全文) 并行召回 → RRF 融合 → weight 重排。
     * 行业生产标配：纯向量搜索对命名实体效果差，BM25 弥补精确匹配短板。
     */
    public List<GrainResult> retrieveHybrid(String question, UUID spaceId, int topK) {
        float[] queryVector = embeddingService.embed(question);
        if (queryVector == null || queryVector.length == 0) {
            return retrieveWithScores(question, spaceId, topK); // fallback
        }
        PGvector queryVec = new PGvector(queryVector);

        // 并行执行 dense + sparse
        var denseFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Map<UUID, Double> simMap = new LinkedHashMap<>();
            List<ExperienceGrain> candidates = jdbc.query("""
                SELECT g.*, 1.0 - (g.embedding <=> ?) AS similarity
                FROM experience_grain g
                WHERE g.space_id = ? AND g.status = 'active' AND g.embedding IS NOT NULL
                  AND (g.quality_score IS NULL OR g.quality_score >= 3.0)
                ORDER BY g.embedding <=> ?
                LIMIT ?
                """,
                (rs, rn) -> {
                    ExperienceGrain g = mapGrainRow(rs, rn);
                    simMap.put(g.getId(), rs.getDouble("similarity"));
                    return g;
                },
                queryVec, spaceId, queryVec, topK * 3
            );
            return new AbstractMap.SimpleEntry<>(candidates, simMap);
        });

        var sparseFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
            bm25Search(question, spaceId, topK));

        var denseEntry = denseFuture.join();
        List<GrainResult> sparse = sparseFuture.join();

        // RRF 融合
        Map<UUID, Double> rrfScores = new LinkedHashMap<>();
        List<ExperienceGrain> denseResults = denseEntry.getKey();
        Map<UUID, Double> denseSims = denseEntry.getValue();

        for (int i = 0; i < denseResults.size(); i++) {
            rrfScores.merge(denseResults.get(i).getId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < sparse.size(); i++) {
            rrfScores.merge(sparse.get(i).grain().getId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }

        // 按 RRF × weight 排序
        Map<UUID, ExperienceGrain> grainMap = new LinkedHashMap<>();
        denseResults.forEach(g -> grainMap.put(g.getId(), g));
        sparse.forEach(r -> grainMap.putIfAbsent(r.grain().getId(), r.grain()));

        Map<UUID, Double> simMap = new LinkedHashMap<>();
        denseSims.forEach(simMap::put);
        sparse.forEach(r -> simMap.merge(r.grain().getId(), r.similarity(), Math::max));

        return rrfScores.entrySet().stream()
            .sorted((a, b) -> Double.compare(
                b.getValue() * getWeight(grainMap.getOrDefault(b.getKey(), null)),
                a.getValue() * getWeight(grainMap.getOrDefault(a.getKey(), null))))
            .limit(topK)
            .map(e -> {
                ExperienceGrain g = grainMap.get(e.getKey());
                double sim = simMap.getOrDefault(e.getKey(), 0.0);
                return new GrainResult(g, round2(sim),
                    round2(sim * getWeight(g)));
            })
            .toList();
    }

    /** 颗粒 + 余弦相似度 + 加权得分 */
    public record GrainResult(ExperienceGrain grain, double similarity, double weightedScore) {}

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private double getWeight(ExperienceGrain g) {
        return g.getWeight() != null ? g.getWeight() : 1.0;
    }

    private ExperienceGrain mapGrainRow(ResultSet rs, int rowNum) throws SQLException {
        return ExperienceGrain.builder()
            .id(rs.getObject("id", UUID.class))
            .spaceId(rs.getObject("space_id", UUID.class))
            .reportId(rs.getObject("report_id", UUID.class))
            .sceneTag(rs.getString("scene_tag"))
            .sceneDescription(rs.getString("scene_description"))
            .expertThought(rs.getString("expert_thought"))
            .standardScript(rs.getString("standard_script"))
            .commonMistakes(rs.getString("common_mistakes"))
            .applicableCondition(rs.getString("applicable_condition"))
            .weight(rs.getDouble("weight"))
            .status(rs.getString("status"))
            .createdAt(rs.getTimestamp("created_at") != null
                ? rs.getTimestamp("created_at").toLocalDateTime() : null)
            .build();
    }
}
