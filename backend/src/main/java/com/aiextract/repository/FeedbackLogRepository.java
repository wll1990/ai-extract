package com.aiextract.repository;

import com.aiextract.model.FeedbackLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 反馈记录数据访问接口。
  * @author AI Extract Team
 */
@Repository
public interface FeedbackLogRepository extends JpaRepository<FeedbackLog, UUID> {
    /**
     * 查询By Skill Id Order By Created At Desc。
     * @param skillId 参数
     * @return 分页结果
     */
    Page<FeedbackLog> findBySkillIdOrderByCreatedAtDesc(UUID skillId, Pageable pageable);
    /**
     * 查询（Skill,Id,Rating,Created,At）。
     * @param skillId skillId
     * @param rating rating
     * @return 分页结果
     */
    Page<FeedbackLog> findBySkillIdAndRatingOrderByCreatedAtDesc(UUID skillId, String rating, Pageable pageable);
    /**
     * 统计（Skill,Id,Rating,Group,Grain,Id）。
     * @param skillId skillId
     * @param rating rating
     * @return 结果列表
     */
    @Query("SELECT COUNT(fl), fl.grainId FROM FeedbackLog fl WHERE fl.skillId = :skillId AND fl.rating = :rating GROUP BY fl.grainId")
    List<Object[]> countBySkillIdAndRatingGroupByGrainId(@Param("skillId") UUID skillId, @Param("rating") String rating);
    /**
     * satisfaction（Stats）— 满意数 & 总数。
     * @param skillId skillId
     * @return [upCount, totalCount]
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN fl.rating = 'up' THEN 1 ELSE 0 END), 0), COUNT(fl) FROM FeedbackLog fl WHERE fl.skillId = :skillId")
    List<Object[]> satisfactionStats(@Param("skillId") UUID skillId);
    /**
     * global（Satisfaction,Stats）。
     * @return [upCount, totalCount]
     */
    @Query("SELECT COALESCE(SUM(CASE WHEN fl.rating = 'up' THEN 1 ELSE 0 END), 0), COUNT(fl) FROM FeedbackLog fl")
    List<Object[]> globalSatisfactionStats();
    /**
     * batch（Satisfaction,Stats）— 按 skillId 分组。
     * @param skillIds skillIds
     * @return [skillId, upCount, totalCount]
     */
    @Query("SELECT fl.skillId, COALESCE(SUM(CASE WHEN fl.rating = 'up' THEN 1 ELSE 0 END), 0), COUNT(fl) FROM FeedbackLog fl WHERE fl.skillId IN :skillIds GROUP BY fl.skillId")
    List<Object[]> batchSatisfactionStats(@Param("skillIds") List<UUID> skillIds);

    /** 按颗粒 ID 查询反馈记录，按时间降序 */
    List<FeedbackLog> findByGrainIdOrderByCreatedAtDesc(UUID grainId, Pageable pageable);

    /** 查询指定颗粒列表中最近 N 天被踩的 grain ID */
    @Query("SELECT DISTINCT fl.grainId FROM FeedbackLog fl WHERE fl.grainId IN :grainIds AND fl.rating = 'down' AND fl.createdAt >= :since")
    java.util.Set<UUID> findDownvotedGrainIds(@Param("grainIds") java.util.List<UUID> grainIds, @Param("since") java.time.LocalDateTime since);

    /** 查询指定颗粒列表中最近 N 天的赞/踩计数，用于拉普拉斯平滑权重计算 */
    @Query("SELECT fl.grainId, " +
           "COALESCE(SUM(CASE WHEN fl.rating = 'up' THEN 1 ELSE 0 END), 0), " +
           "COALESCE(SUM(CASE WHEN fl.rating = 'down' THEN 1 ELSE 0 END), 0) " +
           "FROM FeedbackLog fl WHERE fl.grainId IN :grainIds AND fl.createdAt >= :since " +
           "GROUP BY fl.grainId")
    java.util.List<Object[]> findFeedbackCounts(@Param("grainIds") java.util.List<UUID> grainIds, @Param("since") java.time.LocalDateTime since);

}
