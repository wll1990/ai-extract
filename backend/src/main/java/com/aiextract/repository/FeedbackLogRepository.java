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
    List<Object[]> countBySkillIdAndRatingGroupByGrainId(@Param("skillId") UUID skillId, @Param("rating") String rating);
    /**
     * satisfaction（Stats）。
     * @param skillId skillId
     * @return 结果列表
     */
    List<Object[]> satisfactionStats(@Param("skillId") UUID skillId);
    /**
     * global（Satisfaction,Stats）。
     * @return 结果列表
     */
    List<Object[]> globalSatisfactionStats();
    /**
     * batch（Satisfaction,Stats）。
     * @param skillIds skillIds
     * @return 结果列表
     */
    List<Object[]> batchSatisfactionStats(@Param("skillIds") List<UUID> skillIds);


}
