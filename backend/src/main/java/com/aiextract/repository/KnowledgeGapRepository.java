package com.aiextract.repository;

import com.aiextract.model.KnowledgeGap;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 知识缺口数据访问接口。
  * @author AI Extract Team
 */
@Repository
public interface KnowledgeGapRepository extends JpaRepository<KnowledgeGap, UUID> {
    /**
     * 查询（Skill,Id,Status,Attempted,Query,Count）。
     * @param skillId skillId
     * @param status status
     * @return 结果列表
     */
    List<KnowledgeGap> findBySkillIdAndStatusOrderByAttemptedQueryCountDesc(UUID skillId, String status);
    /**
     * 统计（Skill,Id,Scene,Tag）。
     * @param skillId skillId
     * @param sceneTag sceneTag
     * @return 统计数量
     */
    long countBySkillIdAndSceneTag(@Param("skillId") UUID skillId, @Param("sceneTag") String sceneTag);
    /**
     * 统计（Skill,Id,Status）。
     * @param skillId skillId
     * @param status status
     * @return 统计数量
     */
    long countBySkillIdAndStatus(UUID skillId, String status);
    /**
     * 统计（Status）。
     * @param status status
     * @return 统计数量
     */
    long countByStatus(String status);
    /**
     * 统计（Open,Gaps,Skill,Ids）。
     * @param skillIds skillIds
     * @return 结果列表
     */
    List<Object[]> countOpenGapsBySkillIds(@Param("skillIds") List<UUID> skillIds);
    // ========== pgvector embedding 操作 ==========

    /** 查找没有 embedding 的 open 缺口（用于调用 DashScope 向量化） */
    @Query(value = "SELECT * FROM knowledge_gap WHERE status = 'open' AND embedding IS NULL ORDER BY attempted_query_count DESC LIMIT :limit",
            nativeQuery = true)
    /**
     * 查询（Open,Gaps,Embedding）。
     * @param limit limit
     * @return 结果列表
     */
    List<KnowledgeGap> findOpenGapsWithoutEmbedding(@Param("limit") int limit);
    /**
     * 更新（Embedding）。
     * @param id id
     * @param embedding embedding
     */
    void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embedding);
    /** 找到与给定向量最相似的前 N 条缺口（余弦距离 < 阈值表示相关） */
    @Query(value = "SELECT k.* FROM knowledge_gap k WHERE k.status = 'open' AND k.embedding IS NOT NULL AND k.id != :excludeId ORDER BY k.embedding <=> CAST(:embedding AS VECTOR) ASC LIMIT :limit",
            nativeQuery = true)
    /**
     * 查询（Nearest,Gaps）。
     * @param embedding embedding
     * @param excludeId excludeId
     * @param limit limit
     * @return 结果列表
     */
    List<KnowledgeGap> findNearestGaps(@Param("embedding") String embedding, @Param("excludeId") UUID excludeId, @Param("limit") int limit);
    /** 查询 attempt >= 阈值且已向量化的缺口（用于触发洞察生成） */
    @Query(value = "SELECT * FROM knowledge_gap WHERE status = 'open' AND embedding IS NOT NULL AND attempted_query_count >= :minAttempts ORDER BY attempted_query_count DESC",
            nativeQuery = true)
    /**
     * 查询（Frequent,Gaps,Embedding）。
     * @param minAttempts minAttempts
     * @return 结果列表
     */
    List<KnowledgeGap> findFrequentGapsWithEmbedding(@Param("minAttempts") int minAttempts, Pageable pageable);
    /**
     * cosine（Distance）。
     * @param idA idA
     * @param idB idB
     * @return 计算结果
     */
    Double cosineDistance(@Param("idA") UUID idA, @Param("idB") UUID idB);


}
