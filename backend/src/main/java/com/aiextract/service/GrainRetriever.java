package com.aiextract.service;

import com.aiextract.model.ExperienceGrain;
import com.pgvector.PGvector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import com.aiextract.repository.FeedbackLogRepository;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 语义检索 top-K 经验颗粒
 *
 * 使用 pgvector cosine_distance 算子 (<=>) 按语义相似度排序，
 * 结合 weight 字段做综合打分: relevance = (1 - cosine_distance) * weight
  * @author AI Extract Team
 */
@Slf4j
@Service
public class GrainRetriever {
    private final JdbcTemplate jdbc;
    private final DashScopeEmbeddingService embeddingService;
    private final FeedbackLogRepository feedbackLogRepository;
    private final Executor ragExecutor;

    public GrainRetriever(JdbcTemplate jdbc,
                          DashScopeEmbeddingService embeddingService,
                          FeedbackLogRepository feedbackLogRepository,
                          @Qualifier("ragRetrievalExecutor") Executor ragExecutor) {
        this.jdbc = jdbc;
        this.embeddingService = embeddingService;
        this.feedbackLogRepository = feedbackLogRepository;
        this.ragExecutor = ragExecutor;
    }

    /** 负面反馈回溯天数 */
    private static final int FEEDBACK_LOOKBACK_DAYS = 30;
    /** 拉普拉斯平滑最低权重保护 — 即使全踩也不会跌破 baseWeight × 0.5 */
    private static final double MIN_CONFIDENCE_WEIGHT = 0.5;

    /** 反馈统计计数，用于拉普拉斯平滑权重计算 */
    private record FeedbackCounts(int helpfulCount, int unhelpfulCount) {
        static final FeedbackCounts EMPTY = new FeedbackCounts(0, 0);
    }

    // ============================================================
    // SQL 模板 — 参数化查询，禁止字符串拼接用户输入
    // ============================================================

    /** Dense ANN 检索 SQL（含质量门禁） */
    private static final String DENSE_SQL = """
        SELECT g.*, 1.0 - (g.embedding <=> ?) AS similarity
        FROM experience_grain g
        WHERE g.space_id = ? AND g.status = 'active' AND g.embedding IS NOT NULL
          AND (g.quality_score IS NULL OR g.quality_score >= 3.0)
        ORDER BY g.embedding <=> ?
        LIMIT ?
        """;

    /** Dense ANN 检索 SQL（跳过质量门禁，用于降级链路） */
    private static final String DENSE_SQL_NO_GATE = """
        SELECT g.*, 1.0 - (g.embedding <=> ?) AS similarity
        FROM experience_grain g
        WHERE g.space_id = ? AND g.status = 'active' AND g.embedding IS NOT NULL
        ORDER BY g.embedding <=> ?
        LIMIT ?
        """;

    /** BM25 全文检索 SQL */
    private static final String BM25_SQL = """
        SELECT g.*, ts_rank(g.search_text, plainto_tsquery('zhparser_cfg', ?)) AS bm25_score
        FROM experience_grain g
        WHERE g.space_id = ? AND g.status = 'active' AND g.embedding IS NOT NULL
          AND g.search_text @@ plainto_tsquery('zhparser_cfg', ?)
        ORDER BY bm25_score DESC
        LIMIT ?
        """;

    // ============================================================
    // 公共检索 API
    // ============================================================

    /**
     * 语义检索 — 两步走：先 ANN 命中 HNSW 索引，再 Java 端 weight 重排
     */
    public List<ExperienceGrain> retrieve(String question, UUID spaceId, int topK) {
        return retrieveWithScores(question, spaceId, topK).stream()
                .map(r -> r.grain).collect(java.util.stream.Collectors.toList());
    }

    /** 带相似度分数的语义检索，用于分层标记颗粒质量 */
    public List<GrainResult> retrieveWithScores(String question, UUID spaceId, int topK) {
        return retrieveWithScores(question, spaceId, topK, false);
    }

    /**
     * 带相似度分数的语义检索（可跳过质量门禁）。
     * skipQualityGate=true 时不过滤 quality_score，用于多级降级链路。
     */
    public List<GrainResult> retrieveWithScores(String question, UUID spaceId, int topK, boolean skipQualityGate) {
        float[] queryVector = embeddingService.embed(question);
        if (queryVector == null || queryVector.length == 0) {
            log.error("嵌入服务返回空 question={}", question.substring(0, Math.min(100, question.length())));
            return List.of();
        }
        return retrieveWithScores(queryVector, spaceId, topK, skipQualityGate);
    }

    /**
     * 使用预计算的查询向量做语义检索（避免重复 embedding）。
     * 用于 RagPipelineService 的 fallback 链，同一条 query 多次检索只需 embed 一次。
     */
    public List<GrainResult> retrieveWithScores(float[] queryVector, UUID spaceId, int topK, boolean skipQualityGate) {
        long t0 = System.currentTimeMillis();
        PGvector queryVec = new PGvector(queryVector);
        String sql = skipQualityGate ? DENSE_SQL_NO_GATE : DENSE_SQL;

        // Step 1: ANN 检索 + 记录余弦相似度
        Map<UUID, Double> simMap = new LinkedHashMap<>();
        List<ExperienceGrain> candidates = jdbc.query(sql,
            (rs, rowNum) -> {
                ExperienceGrain g = mapGrainRow(rs, rowNum);
                simMap.put(g.getId(), rs.getDouble("similarity"));
                return g;
            },
            queryVec, spaceId, queryVec, topK * 3
        );

        // Step 2: weight 重排 → 组装结果
        Map<UUID, FeedbackCounts> feedbackCounts = loadFeedbackCounts(candidates);
        List<GrainResult> results = candidates.stream()
                .sorted((a, b) -> Double.compare(
                        simMap.get(b.getId()) * getWeight(b, feedbackCounts),
                        simMap.get(a.getId()) * getWeight(a, feedbackCounts)))
                .limit(topK)
                .map(g -> new GrainResult(g, round2(simMap.get(g.getId())),
                        round2(simMap.get(g.getId()) * getWeight(g, feedbackCounts))))
                .collect(java.util.stream.Collectors.toList());

        log.info("RAG检索完成 {}ms candidates={} results={} scores={} skipQualityGate={}",
                System.currentTimeMillis() - t0, candidates.size(), results.size(),
                results.stream().map(r -> String.format("%.2f", r.similarity)).collect(java.util.stream.Collectors.toList()),
                skipQualityGate);
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
        return retrieveWithScores(question, spaceIds, topK, false);
    }

    /**
     * 多空间语义检索（可跳过质量门禁）。
     * skipQualityGate=true 时不过滤 quality_score，用于降级链路。
     */
    public List<GrainResult> retrieveWithScores(String question, List<UUID> spaceIds, int topK, boolean skipQualityGate) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }
        float[] queryVector = embeddingService.embed(question);
        if (queryVector == null || queryVector.length == 0) {
            log.error("嵌入服务返回空 question={}", question.substring(0, Math.min(100, question.length())));
            return List.of();
        }
        return retrieveWithScores(queryVector, spaceIds, topK, skipQualityGate);
    }

    /**
     * 多空间语义检索（预计算向量 + 可选质量门禁）。
     */
    public List<GrainResult> retrieveWithScores(float[] queryVector, List<UUID> spaceIds, int topK, boolean skipQualityGate) {
        if (spaceIds == null || spaceIds.isEmpty()) {
            return List.of();
        }
        long t0 = System.currentTimeMillis();
        PGvector queryVec = new PGvector(queryVector);
        String sql = skipQualityGate ? DENSE_SQL_NO_GATE : DENSE_SQL;

        // ── Step 1: 多空间并行 ANN 检索（P1-5） ──
        Map<UUID, Double> simMap = new java.util.concurrent.ConcurrentHashMap<>();
        List<ExperienceGrain> candidates = java.util.Collections.synchronizedList(new ArrayList<>());

        List<java.util.concurrent.CompletableFuture<Void>> futures = spaceIds.stream()
            .map(spaceId -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                List<ExperienceGrain> spaceResults = jdbc.query(sql,
                    (rs, rowNum) -> {
                        ExperienceGrain g = mapGrainRow(rs, rowNum);
                        double sim = rs.getDouble("similarity");
                        simMap.merge(g.getId(), sim, Math::max);
                        return g;
                    },
                    queryVec, spaceId, queryVec, topK * 3
                );
                candidates.addAll(spaceResults);
            }, ragExecutor))
            .toList();

        // 等待所有空间查询完成
        java.util.concurrent.CompletableFuture.allOf(
            futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();

        // ── Step 2: 去重 + weight 全局重排 ──
        Map<UUID, FeedbackCounts> feedbackCounts = loadFeedbackCounts(candidates);
        List<GrainResult> results = candidates.stream()
                .collect(Collectors.toMap(
                        ExperienceGrain::getId,
                        g -> g,
                        (a, b) -> a))
                .values().stream()
                .sorted((a, b) -> Double.compare(
                        simMap.get(b.getId()) * getWeight(b, feedbackCounts),
                        simMap.get(a.getId()) * getWeight(a, feedbackCounts)))
                .limit(topK)
                .map(g -> new GrainResult(g,
                        round2(simMap.get(g.getId())),
                        round2(simMap.get(g.getId()) * getWeight(g, feedbackCounts))))
                .collect(Collectors.toList());

        log.info("多空间RAG检索完成 {}ms spaces={} candidates={} results={} scores={} skipQualityGate={}",
                System.currentTimeMillis() - t0, spaceIds.size(), candidates.size(),
                results.size(),
                results.stream().map(r -> String.format("%.2f", r.similarity)).collect(Collectors.toList()),
                skipQualityGate);
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
     *
     * <p>内置 short query 保护：查询无有效中文分词 token 时跳过 BM25，
     * 用单次 ts_debug 调用判断，避免额外数据库往返。</p>
     */
    public List<GrainResult> bm25Search(String query, UUID spaceId, int topK) {
        // short query 保护: 用 ts_debug 判断是否有有效中文分词 token，
        // 同时执行 tsquery 解析 — 一次 DB 往返完成两项检查
        if (query == null || query.isBlank()) return List.of();
        try {
            Integer tokenCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ts_debug('zhparser_cfg', ?) WHERE alias NOT IN ('blank', 'space', 'punctuation')",
                Integer.class, query);
            if (tokenCount == null || tokenCount == 0) {
                return List.of();
            }
        } catch (Exception e) {
            // 降级：zhparser 未安装时回退简单字符数判断
            log.warn("ts_debug 调用失败（zhparser 可能未安装），回退字符数判断: {}", e.getMessage());
            if (query.replaceAll("\\s+", "").length() < 2) {
                return List.of();
            }
        }

        return jdbc.query(BM25_SQL,
            (rs, rowNum) -> {
                ExperienceGrain g = mapGrainRow(rs, rowNum);
                double score = rs.getDouble("bm25_score");
                return new GrainResult(g, round2(score), round2(score * getWeight(g)));
            },
            query, spaceId, query, topK * 3
        );
    }

    /**
     * Hybrid Search — Dense(向量) + Sparse(全文) 并行召回 → RRF 融合 → weight 重排。
     * 行业生产标配：纯向量搜索对命名实体效果差，BM25 弥补精确匹配短板。
     */
    public List<GrainResult> retrieveHybrid(String question, UUID spaceId, int topK) {
        return retrieveHybrid(question, spaceId, topK, false);
    }

    /**
     * Hybrid Search（可跳过质量门禁）。
     * skipQualityGate=true 时不过滤 quality_score，用于多级降级链路。
     *
     * <p>注意：Hybrid 不支持预计算向量优化 — BM25 需要原始 query 文本。
     * Fallback 链的 Level 3（Dense-only）使用 {@link #retrieveWithScores(float[], UUID, int, boolean)} 复用向量。</p>
     */
    public List<GrainResult> retrieveHybrid(String question, UUID spaceId, int topK, boolean skipQualityGate) {
        float[] queryVector;
        try {
            queryVector = embeddingService.embed(question);
        } catch (Exception e) {
            log.warn("Embedding失败，降级为BM25-only检索: {}", e.getMessage());
            return bm25Search(question, spaceId, topK);
        }
        if (queryVector == null || queryVector.length == 0) {
            return bm25Search(question, spaceId, topK);
        }
        PGvector queryVec = new PGvector(queryVector);
        String sql = skipQualityGate ? DENSE_SQL_NO_GATE : DENSE_SQL;

        // 并行执行 dense + sparse
        var denseFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Map<UUID, Double> simMap = new LinkedHashMap<>();
            List<ExperienceGrain> candidates = jdbc.query(sql,
                (rs, rn) -> {
                    ExperienceGrain g = mapGrainRow(rs, rn);
                    simMap.put(g.getId(), rs.getDouble("similarity"));
                    return g;
                },
                queryVec, spaceId, queryVec, topK * 3
            );
            return new AbstractMap.SimpleEntry<>(candidates, simMap);
        }, ragExecutor);

        var sparseFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
            bm25Search(question, spaceId, topK), ragExecutor);

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

        Map<UUID, FeedbackCounts> feedbackCounts = loadFeedbackCounts(new ArrayList<>(grainMap.values()));

        Map<UUID, Double> simMap = new HashMap<>();
        denseSims.forEach(simMap::put);
        sparse.forEach(r -> simMap.merge(r.grain().getId(), r.similarity(), Math::max));

        return rrfScores.entrySet().stream()
            .filter(e -> grainMap.containsKey(e.getKey()))
            .sorted((a, b) -> Double.compare(
                b.getValue() * getWeight(grainMap.get(b.getKey()), feedbackCounts),
                a.getValue() * getWeight(grainMap.get(a.getKey()), feedbackCounts)))
            .limit(topK)
            .map(e -> {
                ExperienceGrain g = grainMap.get(e.getKey());
                // simMap key guaranteed from dense/sparse results that built grainMap
                double sim = simMap.getOrDefault(e.getKey(), 0.0);
                return new GrainResult(g, round2(sim),
                    round2(sim * getWeight(g, feedbackCounts)));
            })
            .toList();
    }

    // ============================================================
    // Weight 计算
    // ============================================================

    /** 颗粒 + 余弦相似度 + 加权得分 */
    public record GrainResult(ExperienceGrain grain, double similarity, double weightedScore) {}

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private double getWeight(ExperienceGrain g) {
        return g.getWeight() != null ? g.getWeight() : 1.0;
    }

    /**
     * 拉普拉斯平滑权重 — Beta 分布后验均值 + 最低权重保护。
     *
     * <p>公式：confidenceWeight = (helpful+1) / (helpful+unhelpful+2)
     *    finalWeight = baseWeight × max({@link #MIN_CONFIDENCE_WEIGHT}, confidenceWeight)</p>
     *
     * <p>效果：1赞1踩 → weight×0.67（低置信度，惩罚轻）
     *          50赞50踩 → weight×0.51（高置信度，惩罚重）
     *          100赞0踩 → weight×0.99（高度信任）
     *          0踩无反馈 → weight×1.0（无先验，默认中性）</p>
     */
    private double getWeight(ExperienceGrain g, Map<UUID, FeedbackCounts> feedbackCounts) {
        double baseWeight = getWeight(g);
        FeedbackCounts fc = feedbackCounts != null ? feedbackCounts.getOrDefault(g.getId(), FeedbackCounts.EMPTY) : FeedbackCounts.EMPTY;
        if (fc.helpfulCount == 0 && fc.unhelpfulCount == 0) {
            return baseWeight;
        }
        // 拉普拉斯平滑：Beta(helpful+1, unhelpful+1) 后验均值
        double confidenceWeight = (double)(fc.helpfulCount + 1) / (fc.helpfulCount + fc.unhelpfulCount + 2);
        return baseWeight * Math.max(MIN_CONFIDENCE_WEIGHT, confidenceWeight);
    }

    /**
     * 批量加载候选颗粒的反馈统计（赞/踩计数），用于拉普拉斯平滑权重计算。
     * 查询最近 {@link #FEEDBACK_LOOKBACK_DAYS} 天的 feedback_log 记录。
     *
     * <p>性能注意：每次检索都会查询 feedback_log 表。当 feedback_log 数据量大时，
     * 建议在 (grain_id, created_at) 上建复合索引，或按 P1 方案迁移到 Redis ZSet 缓存。</p>
     */
    private Map<UUID, FeedbackCounts> loadFeedbackCounts(List<ExperienceGrain> candidates) {
        if (candidates.isEmpty()) return Map.of();
        List<UUID> grainIds = candidates.stream().map(ExperienceGrain::getId).toList();
        try {
            List<Object[]> rows = feedbackLogRepository.findFeedbackCounts(
                grainIds, LocalDateTime.now().minusDays(FEEDBACK_LOOKBACK_DAYS));
            Map<UUID, FeedbackCounts> map = new HashMap<>();
            for (Object[] row : rows) {
                UUID grainId = (UUID) row[0];
                int helpful = ((Number) row[1]).intValue();
                int unhelpful = ((Number) row[2]).intValue();
                map.put(grainId, new FeedbackCounts(helpful, unhelpful));
            }
            return map;
        } catch (Exception e) {
            log.debug("加载反馈统计失败: {}", e.getMessage());
            return Map.of();
        }
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
