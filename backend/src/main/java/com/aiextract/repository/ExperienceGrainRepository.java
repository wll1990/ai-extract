package com.aiextract.repository;

import com.aiextract.model.ExperienceGrain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 经验颗粒数据访问接口
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Repository
public interface ExperienceGrainRepository extends JpaRepository<ExperienceGrain, UUID> {

    /**
     * 按空间ID查询所有经验颗粒
     *
     * @param spaceId 空间ID
     * @return 颗粒列表
     */
    List<ExperienceGrain> findBySpaceId(UUID spaceId);
    org.springframework.data.domain.Page<ExperienceGrain> findBySpaceId(UUID spaceId, org.springframework.data.domain.Pageable pageable);

    /**
     * 每场景最佳颗粒 — PostgreSQL DISTINCT ON，一条查询替代 Java 内存 groupGrainsByScene。
     * 返回每场景标签、场景描述、常见误区、质量分。
     */
    @Query(value = "SELECT DISTINCT ON (scene_tag) scene_tag, " +
            "COALESCE(scene_description, '') as scene_description, " +
            "COALESCE(common_mistakes, '') as common_mistakes, " +
            "COALESCE(quality_score, 0) as quality_score " +
            "FROM experience_grain " +
            "WHERE space_id = :spaceId AND status = 'active' AND scene_tag IS NOT NULL " +
            "ORDER BY scene_tag, quality_score DESC NULLS LAST",
            nativeQuery = true)
    List<Object[]> findBestGrainsPerScene(@Param("spaceId") UUID spaceId);

    /**
     * 每场景颗粒计数 — DB 层 GROUP BY，替代 Java 内存遍历。
     */
    @Query("SELECT g.sceneTag, COUNT(g) FROM ExperienceGrain g " +
           "WHERE g.spaceId = :spaceId AND g.status = 'active' AND g.sceneTag IS NOT NULL " +
           "GROUP BY g.sceneTag")
    List<Object[]> countGrainsByScene(@Param("spaceId") UUID spaceId);

    /**
     * 批量：多空间每场景颗粒计数 — 一次 IN 查询替代 N 次单空间查询。
     *
     * @param spaceIds 空间 ID 列表
     * @return [sceneTag, count] 数组列表
     */
    @Query("SELECT g.sceneTag, COUNT(g) FROM ExperienceGrain g " +
           "WHERE g.spaceId IN :spaceIds AND g.status = 'active' AND g.sceneTag IS NOT NULL " +
           "GROUP BY g.sceneTag")
    List<Object[]> countGrainsBySceneInSpaceIds(@Param("spaceIds") List<UUID> spaceIds);

    /**
     * 批量：多空间每场景最佳颗粒 — PostgreSQL DISTINCT ON，一次查询替代 N 次。
     *
     * @param spaceIds 空间 ID 列表
     * @return [sceneTag, sceneDescription] 数组列表
     */
    @Query(value = "SELECT DISTINCT ON (scene_tag) scene_tag, " +
            "COALESCE(scene_description, '') as scene_description " +
            "FROM experience_grain " +
            "WHERE space_id IN (:spaceIds) AND status = 'active' AND scene_tag IS NOT NULL " +
            "ORDER BY scene_tag, quality_score DESC NULLS LAST",
            nativeQuery = true)
    List<Object[]> findBestGrainsPerSceneInSpaceIds(@Param("spaceIds") List<UUID> spaceIds);

    /**
     * 批量：多空间去重 (spaceId, sceneTag) 对 — 供电 `getPracticeScenes` 聚合覆盖成员数。
     *
     * @param spaceIds 空间 ID 列表
     * @return [spaceId, sceneTag] 数组列表
     */
    @Query("SELECT DISTINCT g.spaceId, g.sceneTag FROM ExperienceGrain g " +
           "WHERE g.spaceId IN :spaceIds AND g.status = 'active' AND g.sceneTag IS NOT NULL AND g.sceneTag <> ''")
    List<Object[]> findDistinctSceneTagsBySpaceIdIn(@Param("spaceIds") List<UUID> spaceIds);

    /**
     * 按报告ID查询所有经验颗粒
     *
     * @param reportId 报告ID
     * @return 颗粒列表
     */
    List<ExperienceGrain> findByReportId(UUID reportId);

    @Query("SELECT DISTINCT g.reportId, g.sceneTag FROM ExperienceGrain g " +
           "WHERE g.reportId IN :reportIds AND g.sceneTag IS NOT NULL AND g.sceneTag <> ''")
    List<Object[]> findDistinctSceneTagsByReportIdIn(@Param("reportIds") List<UUID> reportIds);

    /**
     * 按场景标签查询经验颗粒
     *
     * @param sceneTag 场景标签
     * @return 颗粒列表
     */
    List<ExperienceGrain> findBySceneTag(String sceneTag);
    /**
     * 查询By Source Material Id。
     * @param sourceMaterialId 参数
     * @return 结果列表
     */
    List<ExperienceGrain> findBySourceMaterialId(UUID sourceMaterialId);
    /**
     * 按来源访谈会话ID查询颗粒。
     * @param sourceInterviewId 访谈会话ID
     * @return 结果列表
     */
    List<ExperienceGrain> findBySourceInterviewId(UUID sourceInterviewId);
    /**
     * 统计（Space,Id）。
     * @param spaceId spaceId
     * @return 统计数量
     */
    long countBySpaceId(UUID spaceId);
    /**
     * 统计（Space,Id,Status）。
     * @param spaceId spaceId
     * @param status status
     * @return 统计数量
     */
    long countBySpaceIdAndStatus(UUID spaceId, String status);
    /** 统计所有活跃颗粒数（跨空间），用于 public/stats */
    long countByStatus(String status);
    /**
     * 统计（Scene,Tags,Space,Id,Status）。
     * @param spaceId spaceId
     * @param status status
     * @return 统计数量
     */
    long countDistinctSceneTagsBySpaceIdAndStatus(@Param("spaceId") UUID spaceId, @Param("status") String status);
    /**
     * 统计（Space,Id）。
     * @param spaceIds spaceIds
     * @return 结果列表
     */
    @Query("SELECT eg.spaceId, COUNT(eg) FROM ExperienceGrain eg WHERE eg.spaceId IN :spaceIds GROUP BY eg.spaceId")
    List<Object[]> countBySpaceIdIn(@Param("spaceIds") List<UUID> spaceIds);
    /** 按空间列表分页查询（仅有 embedding 的活跃颗粒） */
    @Query("SELECT g FROM ExperienceGrain g WHERE g.spaceId IN :spaceIds AND g.status = 'active' AND g.embedding IS NOT NULL")
    List<ExperienceGrain> findBySpaceIdIn(@Param("spaceIds") List<UUID> spaceIds,
            org.springframework.data.domain.Pageable pageable);

    /** 按空间列表查询所有颗粒（无分页，无 embedding 过滤） */
    List<ExperienceGrain> findAllBySpaceIdIn(@Param("spaceIds") List<UUID> spaceIds);
    /**
     * increment（Helpful）。
     * @param id id
     */
    @Modifying
    @Query("UPDATE ExperienceGrain eg SET eg.helpfulCount = eg.helpfulCount + 1 WHERE eg.id = :id")
    void incrementHelpful(@Param("id") UUID id);
    /**
     * increment（Unhelpful）。
     * @param id id
     */
    @Modifying
    @Query("UPDATE ExperienceGrain eg SET eg.unhelpfulCount = eg.unhelpfulCount + 1 WHERE eg.id = :id")
    void incrementUnhelpful(@Param("id") UUID id);
    /**
     * 查询（Embedding）。
     * @return 查询结果
     */
    @Query("SELECT eg FROM ExperienceGrain eg WHERE eg.embedding IS NULL")
    org.springframework.data.domain.Page<ExperienceGrain> findWithoutEmbedding(org.springframework.data.domain.Pageable pageable);
    /**
     * 查询（Space,Id,Status）。
     * @param spaceId spaceId
     * @param status status
     * @return 结果列表
     */
    List<ExperienceGrain> findBySpaceIdAndStatus(UUID spaceId, String status);
    /**
     * 查询（Space,Id,Scene,Tag,Status）。
     * @param spaceId spaceId
     * @param sceneTag sceneTag
     * @param status status
     * @return 结果列表
     */
    List<ExperienceGrain> findBySpaceIdAndSceneTagAndStatus(UUID spaceId, String sceneTag, String status);
    /**
     * 查询（Space,Id,Status,Scene,Tag）。
     * @param spaceId spaceId
     * @param status status
     * @return 结果列表
     */
    List<ExperienceGrain> findBySpaceIdAndStatusAndSceneTagNotNull(@Param("spaceId") UUID spaceId, @Param("status") String status);
    /** 取空间下前 5 个不重复场景标签（避免加载全部颗粒） */
    @Query("SELECT DISTINCT g.sceneTag FROM ExperienceGrain g WHERE g.spaceId = :spaceId AND g.sceneTag IS NOT NULL AND g.sceneTag <> ''")
    List<String> findTop5DistinctSceneTagsBySpaceId(@Param("spaceId") UUID spaceId,
            org.springframework.data.domain.Pageable pageable);
    /**
     * 查询（Top5,Scene,Tags,Space,Id）。
     * @param spaceId spaceId
     * @return 结果列表
     */
    default List<String> findTop5DistinctSceneTagsBySpaceId(UUID spaceId) {
        return findTop5DistinctSceneTagsBySpaceId(spaceId,
                org.springframework.data.domain.PageRequest.of(0, 5));
    }
    /**
     * 查询（Space,Id,Helpful,Count）。
     * @param spaceId spaceId
     * @return 结果列表
     */
    List<ExperienceGrain> findTopBySpaceIdOrderByHelpfulCountDesc(@Param("spaceId") UUID spaceId, org.springframework.data.domain.Pageable pageable);
    /**
     * 查询（Space,Id,Unhelpful,Count）。
     * @param spaceId spaceId
     * @return 结果列表
     */
    List<ExperienceGrain> findTopBySpaceIdOrderByUnhelpfulCountDesc(@Param("spaceId") UUID spaceId, org.springframework.data.domain.Pageable pageable);
    /**
     * 统计（Space,Id,Unhelpful,Count）。
     * @param spaceId spaceId
     * @param threshold threshold
     * @return 统计数量
     */
    long countBySpaceIdAndUnhelpfulCountGreaterThanEqual(@Param("spaceId") UUID spaceId, @Param("threshold") int threshold);
    /**
     * 更新（Embedding）。
     * @param id id
     * @param embedding embedding
     */
    @Modifying
    @Query(value = "UPDATE experience_grain SET embedding = CAST(:embedding AS VECTOR) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embedding);

    // ═══════════════════════════════════════════════════════════
    // SkillStatsScheduler 用 — 替代 findAll() 全表扫描
    // ═══════════════════════════════════════════════════════════

    /** 查状态为 active 且有反馈的颗粒（权重调权用） */
    @Query("SELECT g FROM ExperienceGrain g WHERE g.status = :status AND (g.helpfulCount > 0 OR g.unhelpfulCount > 0)")
    List<ExperienceGrain> findByStatusAndHasFeedback(@Param("status") String status);

    /** 查低质活跃颗粒（多次被踩且很少被赞） */
    @Query("SELECT g FROM ExperienceGrain g WHERE g.status = :status AND g.unhelpfulCount >= :minUnhelpful AND g.helpfulCount < :maxHelpful")
    List<ExperienceGrain> findByStatusAndLowQuality(@Param("status") String status,
            @Param("minUnhelpful") int minUnhelpful, @Param("maxHelpful") int maxHelpful);

    /** 查有足够反馈样本的活跃颗粒（qualityScore 动态更新用） */
    @Query("SELECT g FROM ExperienceGrain g WHERE g.status = :status AND g.qualityScore IS NOT NULL AND (g.helpfulCount + g.unhelpfulCount) >= :minFeedback")
    List<ExperienceGrain> findByStatusAndEnoughFeedback(@Param("status") String status,
            @Param("minFeedback") int minFeedback);

}
