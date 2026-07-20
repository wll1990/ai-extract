package com.aiextract.repository;

import com.aiextract.model.AutoInsight;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 自动发现洞察 Repository。
  * @author AI Extract Team
 */
@Repository
public interface AutoInsightRepository extends JpaRepository<AutoInsight, UUID> {
    /**
     * 查询By Skill Id And Status Order By Created At Desc。
     * @param skillId 参数
     * @param status 参数
     * @return 结果列表
     */
    List<AutoInsight> findBySkillIdAndStatusOrderByCreatedAtDesc(UUID skillId, String status, Pageable pageable);
    /**
     * 查询（Status,Created,At）。
     * @param status status
     * @return 结果列表
     */
    List<AutoInsight> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    /**
     * 查询（Skill,Id,Severity,Status,Created,At）。
     * @param skillId skillId
     * @param severity severity
     * @param status status
     * @return 结果列表
     */
    List<AutoInsight> findBySkillIdAndSeverityAndStatusOrderByCreatedAtDesc(
            UUID skillId, String severity, String status, Pageable pageable);
    /**
     * 查询（Severity,Status,Created,At）。
     * @param severity severity
     * @param status status
     * @return 结果列表
     */
    List<AutoInsight> findBySeverityAndStatusOrderByCreatedAtDesc(
            String severity, String status, Pageable pageable);
    /**
     * 统计（Active,Severity）。
     * @return 结果列表
     */
    List<Object[]> countActiveBySeverity();
    /**
     * 统计（Active,Severity,For,Skill）。
     * @param skillId skillId
     * @return 结果列表
     */
    List<Object[]> countActiveBySeverityForSkill(@Param("skillId") UUID skillId);
    /**
     * 统计（Status）。
     * @param status status
     * @return 统计数量
     */
    long countByStatus(String status);
    /**
     * 统计（Skill,Id,Status）。
     * @param skillId skillId
     * @param status status
     * @return 统计数量
     */
    long countBySkillIdAndStatus(UUID skillId, String status);


}
