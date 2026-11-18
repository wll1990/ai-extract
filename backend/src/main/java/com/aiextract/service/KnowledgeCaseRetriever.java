package com.aiextract.service;

import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 知识库 + 案例库语义检索器 —— pgvector 余弦 ANN，企业维度隔离 + 时效过滤 + 相似度阈值。
 *
 * <p>Phase B 独立检索器（P2-8），全新文件，不碰 {@link GrainRetriever}、不混入经验颗粒检索主路径。
 * 检索范围：status=active 且未过失效时间，且「本企业私有 + 平台公共」。案例按 company_id 检索
 * （P2-9：space_id 不参与检索）。</p>
 *
 * <p>{@link #retrieve(String, UUID, int)} 一次嵌入同时返回知识 + 案例命中，供
 * {@code ChatStreamService} 在 grain 检索后追加合并（T12），避免同 query 重复嵌入。
 * 检索内置相似度下限（低于阈值不返回，避免无关结果污染 prompt 与溯源）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeCaseRetriever {

    /**
     * 知识默认最低相似度阈值。
     *
     * <p>实测 text-embedding-v4 下「无关中文短句」的余弦相似度集中在 0.36–0.42，
     * 原 0.35 等于不设门槛（「客户骂我」会命中「退款口径」「竞品对比」）。
     * 0.45 与案例阈值一起挡掉这条噪声基线，同时给真实事实型命中留足余量。</p>
     */
    public static final double DEFAULT_KNOWLEDGE_MIN_SIMILARITY = 0.45;

    /** 案例默认最低相似度阈值（叙事型，分布平缓，需抬到能挡掉「泛销售」基底噪音） */
    public static final double DEFAULT_CASE_MIN_SIMILARITY = 0.50;

    /**
     * 检索测试专用的「诊断下限」：低于线上阈值的近失命中也会返回，
     * 由调用方标记为「不会进入分身」，便于回答「为什么没引用」。
     */
    public static final double DIAGNOSTIC_KNOWLEDGE_MIN_SIMILARITY = 0.25;

    public static final double DIAGNOSTIC_CASE_MIN_SIMILARITY = 0.30;

    private final JdbcTemplate jdbc;
    private final DashScopeEmbeddingService embeddingService;

    /** 知识命中 */
    public record KnowledgeHit(UUID id, String title, String content, String category,
                               String source, double similarity) {
    }

    /**
     * 案例命中。
     *
     * <p>{@code customerProfile/emotionChange/advanceNodes} 供对练「客户角色」使用：
     * 只暴露客户侧信息，不把标准话术/动作喂给扮演客户的模型。</p>
     */
    public record CaseHit(UUID id, String title, String sceneTag, String funnelStage, String result,
                          String customerProfile, String emotionChange, String advanceNodes,
                          String objection, String keyActions, String scripts, String reusablePoints,
                          String failurePoints, String source, double similarity) {
    }

    /** 一次检索的聚合结果：知识 + 案例命中。 */
    public record KnowledgeCaseHits(List<KnowledgeHit> knowledge, List<CaseHit> cases) {
    }

    /** 知识 ANN 检索 SQL：company 维度（本企业 + 公共）+ active + 未过期 + 相似度下限 */
    private static final String KNOWLEDGE_SQL = """
            SELECT k.id, k.title, k.content, k.category, k.source,
                   1.0 - (k.embedding <=> ?) AS similarity
            FROM knowledge_entry k
            WHERE (k.company_id = ? OR k.company_id IS NULL)
              AND k.status = 'active'
              AND (k.effective_at IS NULL OR k.effective_at <= now())
              AND (k.expire_at IS NULL OR k.expire_at > now())
              AND k.embedding IS NOT NULL
              AND (k.embedding <=> ?) <= ?
            ORDER BY k.embedding <=> ?
            LIMIT ?
            """;

    /** 案例 ANN 检索 SQL（同知识，含相似度下限） */
    private static final String CASE_SQL = """
            SELECT c.id, c.title, c.scene_tag, c.funnel_stage, c.result,
                   c.customer_profile, c.emotion_change, c.advance_nodes,
                   c.objection, c.key_actions, c.scripts, c.reusable_points, c.failure_points, c.source,
                   1.0 - (c.embedding <=> ?) AS similarity
            FROM industry_case c
            WHERE (c.company_id = ? OR c.company_id IS NULL)
              AND c.status = 'active'
              AND (c.effective_at IS NULL OR c.effective_at <= now())
              AND (c.expire_at IS NULL OR c.expire_at > now())
              AND c.embedding IS NOT NULL
              AND (c.embedding <=> ?) <= ?
            ORDER BY c.embedding <=> ?
            LIMIT ?
            """;

    /** 是否存在可检索的 active 知识/案例（索引命中 EXISTS，用于跳过无数据时的嵌入调用） */
    private static final String HAS_ANY_SQL = """
            SELECT EXISTS(
                SELECT 1 FROM knowledge_entry
                WHERE (company_id = ? OR company_id IS NULL)
                  AND status = 'active'
                  AND (effective_at IS NULL OR effective_at <= now())
                  AND (expire_at IS NULL OR expire_at > now())
                  AND embedding IS NOT NULL
            ) OR EXISTS(
                SELECT 1 FROM industry_case
                WHERE (company_id = ? OR company_id IS NULL)
                  AND status = 'active'
                  AND (effective_at IS NULL OR effective_at <= now())
                  AND (expire_at IS NULL OR expire_at > now())
                  AND embedding IS NOT NULL
            )
            """;

    private static final RowMapper<KnowledgeHit> KNOWLEDGE_MAPPER = (rs, i) -> new KnowledgeHit(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("content"),
            rs.getString("category"),
            rs.getString("source"),
            rs.getDouble("similarity"));

    private static final RowMapper<CaseHit> CASE_MAPPER = (rs, i) -> new CaseHit(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("scene_tag"),
            rs.getString("funnel_stage"),
            rs.getString("result"),
            rs.getString("customer_profile"),
            rs.getString("emotion_change"),
            rs.getString("advance_nodes"),
            rs.getString("objection"),
            rs.getString("key_actions"),
            rs.getString("scripts"),
            rs.getString("reusable_points"),
            rs.getString("failure_points"),
            rs.getString("source"),
            rs.getDouble("similarity"));

    /**
     * 是否存在可检索的 active 知识或案例（company 维度）。
     *
     * <p>供调用方在嵌入前做廉价预检：无数据时跳过 DashScope embed，避免拖慢无知识库的既有对话流程。</p>
     */
    public boolean hasAnyActive(UUID companyId) {
        Boolean exists = jdbc.queryForObject(HAS_ANY_SQL, Boolean.class, companyId, companyId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 一次嵌入同时检索知识 + 案例（按语义相似度降序）。知识与案例用独立阈值：
     * 知识是事实型、相似度分布尖锐（低阈值即可高精度）；案例是叙事型、分布平缓，
     * 需更高阈值挡掉「泛销售」基底噪音。
     *
     * @param query                  查询文本
     * @param companyId              当前企业 ID（null 表示仅公共知识）
     * @param topK                   每类最大返回数
     * @param knowledgeMinSimilarity 知识最低相似度阈值（0-1）
     * @param caseMinSimilarity      案例最低相似度阈值（0-1）
     */
    public KnowledgeCaseHits retrieve(String query, UUID companyId, int topK,
                                      double knowledgeMinSimilarity, double caseMinSimilarity) {
        float[] queryVector = embeddingService.embed(query);
        if (queryVector == null || queryVector.length == 0) {
            log.warn("知识/案例检索嵌入失败 query={}", query.substring(0, Math.min(100, query.length())));
            return new KnowledgeCaseHits(List.of(), List.of());
        }
        PGvector vec = new PGvector(queryVector);
        // cosine_distance = 1 - similarity，故 similarity >= min 等价于 distance <= 1 - min
        double knowledgeMaxDistance = 1.0 - knowledgeMinSimilarity;
        double caseMaxDistance = 1.0 - caseMinSimilarity;
        List<KnowledgeHit> knowledge = jdbc.query(KNOWLEDGE_SQL, KNOWLEDGE_MAPPER,
                vec, companyId, vec, knowledgeMaxDistance, vec, topK);
        List<CaseHit> cases = jdbc.query(CASE_SQL, CASE_MAPPER,
                vec, companyId, vec, caseMaxDistance, vec, topK);
        log.debug("知识/案例检索命中 knowledge={} cases={}", knowledge.size(), cases.size());
        return new KnowledgeCaseHits(knowledge, cases);
    }

    /** 使用默认相似度阈值检索（见 {@link #DEFAULT_KNOWLEDGE_MIN_SIMILARITY} / {@link #DEFAULT_CASE_MIN_SIMILARITY}）。 */
    public KnowledgeCaseHits retrieve(String query, UUID companyId, int topK) {
        return retrieve(query, companyId, topK, DEFAULT_KNOWLEDGE_MIN_SIMILARITY, DEFAULT_CASE_MIN_SIMILARITY);
    }

    /**
     * 检索知识条目（按语义相似度降序）。
     *
     * @param query     查询文本
     * @param companyId 当前企业 ID（null 表示仅公共知识）
     * @param topK      最大返回数
     */
    public List<KnowledgeHit> retrieveKnowledge(String query, UUID companyId, int topK) {
        return retrieve(query, companyId, topK).knowledge();
    }

    /**
     * 检索案例（按语义相似度降序）。
     */
    public List<CaseHit> retrieveCases(String query, UUID companyId, int topK) {
        return retrieve(query, companyId, topK).cases();
    }
}
