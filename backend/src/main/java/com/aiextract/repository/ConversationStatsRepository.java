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
    List<Object[]> statsOverview(@Param("skillId") UUID skillId, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    /**
     * rag（Distribution）。
     * @param skillId skillId
     * @return 结果列表
     */
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
    List<Object[]> globalOverview(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
    /**
     * batch（Stats,Overview）。
     * @param skillIds skillIds
     * @param start start
     * @param end end
     * @return 结果列表
     */
    List<Object[]> batchStatsOverview(@Param("skillIds") List<UUID> skillIds, @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);


}
