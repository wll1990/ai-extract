package com.aiextract.repository;

import com.aiextract.model.ConversationStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 对话统计数据访问接口。
  * @author AI Extract Team
 */
@Repository
public interface ConversationStatsRepository extends JpaRepository<ConversationStats, UUID> {
    /**
     * stats（Overview）。
     * @param skillId skillId
     * @param start start
     * @param end end
     * @return 结果列表
     */
    @Query("SELECT COUNT(DISTINCT cs.conversationId), COUNT(DISTINCT cs.userId) FROM ConversationStats cs WHERE cs.skillId = :skillId AND cs.createdAt BETWEEN :start AND :end")
    List<Object[]> statsOverview(@Param("skillId") UUID skillId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    /**
     * rag（Distribution）— RAG 匹配分布：高匹配 / 参考 / 无匹配的合计。
     * @param skillId skillId
     * @return 结果列表
     */
    @Query("SELECT COALESCE(SUM(cs.ragHighCount), 0), COALESCE(SUM(cs.ragRefCount), 0), COALESCE(SUM(cs.ragNoneCount), 0) FROM ConversationStats cs WHERE cs.skillId = :skillId")
    List<Object[]> ragDistribution(@Param("skillId") UUID skillId);
    /**
     * 查询（Skill,Id,Test）。
     * @param skillId skillId
     * @return 分页结果
     */
    Page<ConversationStats> findBySkillIdAndIsTestFalse(UUID skillId, Pageable pageable);
    /**
     * global（Overview）。
     * @param start start
     * @param end end
     * @return 结果列表
     */
    @Query("SELECT COUNT(DISTINCT cs.conversationId), COUNT(DISTINCT cs.userId) FROM ConversationStats cs WHERE cs.createdAt BETWEEN :start AND :end")
    List<Object[]> globalOverview(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    /**
     * batch（Stats,Overview）。
     * @param skillIds skillIds
     * @param start start
     * @param end end
     * @return 结果列表
     */
    @Query("SELECT cs.skillId, COUNT(DISTINCT cs.conversationId), COUNT(DISTINCT cs.userId), COALESCE(SUM(cs.ragHighCount),0), COALESCE(SUM(cs.ragRefCount),0), COALESCE(SUM(cs.ragNoneCount),0), MAX(cs.createdAt) FROM ConversationStats cs WHERE cs.skillId IN :skillIds AND cs.createdAt BETWEEN :start AND :end GROUP BY cs.skillId")
    List<Object[]> batchStatsOverview(@Param("skillIds") List<UUID> skillIds, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 批量互动统计（排除 is_test）。
     * SkillStatsScheduler 使用此方法，确保管理员测试对话不污染用户侧统计。
     */
    @Query("SELECT cs.skillId, COUNT(DISTINCT cs.conversationId), COUNT(DISTINCT cs.userId), COALESCE(SUM(cs.ragHighCount),0), COALESCE(SUM(cs.ragRefCount),0), COALESCE(SUM(cs.ragNoneCount),0), MAX(cs.createdAt) FROM ConversationStats cs WHERE cs.skillId IN :skillIds AND cs.isTest = false AND cs.createdAt BETWEEN :start AND :end GROUP BY cs.skillId")
    List<Object[]> batchStatsOverviewExcludeTest(@Param("skillIds") List<UUID> skillIds, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 组织分身 stats 聚合 — 按 skillId + userId 分组，Java 端再跨 skill 去重 user。
     * @param skillIds 成员分身 ID 列表
     * @param start    统计窗口起始
     * @param end      统计窗口结束
     * @return [skillId, convCount, userId, maxCreatedAt]
     */
    @Query("SELECT cs.skillId, COUNT(DISTINCT cs.conversationId), cs.userId, MAX(cs.createdAt) "
         + "FROM ConversationStats cs "
         + "WHERE cs.skillId IN :skillIds AND cs.isTest = false "
         + "AND cs.createdAt BETWEEN :start AND :end "
         + "GROUP BY cs.skillId, cs.userId")
    List<Object[]> orgBatchStatsRaw(@Param("skillIds") List<UUID> skillIds,
                                    @Param("start") LocalDateTime start,
                                    @Param("end") LocalDateTime end);

    /** 今日活动 — 跨技能去重对话+用户数 */
    @Query("SELECT COUNT(DISTINCT cs.conversationId), COUNT(DISTINCT cs.userId) " +
           "FROM ConversationStats cs WHERE cs.skillId IN :skillIds " +
           "AND cs.createdAt >= :since AND cs.isTest = false")
    List<Object[]> todayActivity(@Param("skillIds") List<UUID> skillIds, @Param("since") LocalDateTime since);

    /** 本周用户活跃 TOP 10 — 按 userId 聚合对话数降序 */
    @Query("SELECT cs.userId, COUNT(DISTINCT cs.conversationId) " +
           "FROM ConversationStats cs WHERE cs.skillId IN :skillIds " +
           "AND cs.createdAt >= :since AND cs.isTest = false " +
           "GROUP BY cs.userId ORDER BY COUNT(DISTINCT cs.conversationId) DESC")
    List<Object[]> userActivity(@Param("skillIds") List<UUID> skillIds, @Param("since") LocalDateTime since);

    /** 个人对话数 — 指定用户的去重对话计数 */
    @Query("SELECT COUNT(DISTINCT cs.conversationId) FROM ConversationStats cs " +
           "WHERE cs.userId = :userId AND cs.createdAt >= :since AND cs.isTest = false")
    long myConversations(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    /** 个人最近使用分身 — 按 skillId 分组聚合 */
    @Query("SELECT cs.skillId, COUNT(DISTINCT cs.conversationId), MAX(cs.createdAt) " +
           "FROM ConversationStats cs WHERE cs.userId = :userId " +
           "AND cs.isTest = false GROUP BY cs.skillId " +
           "ORDER BY MAX(cs.createdAt) DESC")
    List<Object[]> mySkills(@Param("userId") UUID userId);

    /** 个人对练数 — 按 userId + mode='practice' + 时间窗口 */
    @Query("SELECT COUNT(DISTINCT cs.conversationId) FROM ConversationStats cs " +
           "WHERE cs.userId = :userId AND cs.mode = 'practice' " +
           "AND cs.createdAt >= :since AND cs.isTest = false")
    long myPracticeCount(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    /** 7 天对话趋势 — 按日期分组计数（native query for DATE() compatibility） */
    @Query(value = "SELECT DATE(cs.created_at) as d, COUNT(DISTINCT cs.conversation_id) " +
           "FROM conversation_stats cs WHERE cs.skill_id IN (:skillIds) " +
           "AND cs.created_at >= :since AND cs.is_test = false " +
           "GROUP BY DATE(cs.created_at) ORDER BY d",
           nativeQuery = true)
    List<Object[]> dailyTrend(@Param("skillIds") List<UUID> skillIds, @Param("since") LocalDateTime since);

}
