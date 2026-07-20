package com.aiextract.service;

import com.aiextract.common.TraceContext;
import com.aiextract.model.ExperienceGrain;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.UUID;

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
        String vectorStr = arrayToPgVector(queryVector);

        // Step 1: ANN 检索 + 记录余弦相似度
        Map<UUID, Double> simMap = new LinkedHashMap<>();
        List<ExperienceGrain> candidates = jdbc.query("""
            SELECT g.*, 1.0 - (g.embedding <=> ?::vector) AS similarity
            FROM experience_grain g
            WHERE g.space_id = ? AND g.status = 'active' AND g.embedding IS NOT NULL
            ORDER BY g.embedding <=> ?::vector
            LIMIT ?
            """,
            (rs, rowNum) -> {
                ExperienceGrain g = mapGrainRow(rs, rowNum);
                simMap.put(g.getId(), rs.getDouble("similarity"));
                return g;
            },
            vectorStr, spaceId, vectorStr, topK * 3
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

    /** 颗粒 + 余弦相似度 + 加权得分 */
    public record GrainResult(ExperienceGrain grain, double similarity, double weightedScore) {}

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private double getWeight(ExperienceGrain g) {
        return g.getWeight() != null ? g.getWeight() : 1.0;
    }

    private String arrayToPgVector(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) {

                sb.append(",");

            }
            sb.append(vec[i]);
        }
        return sb.append("]").toString();
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
