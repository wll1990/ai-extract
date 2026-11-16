package com.aiextract.repository;

import com.aiextract.model.AutoInsight;
import com.aiextract.model.CandidateGrain;
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

    /** 企业范围过滤必须在 SQL 里完成，先分页再内存过滤会漏数据（K22）。 */
    List<AutoInsight> findBySkillIdInAndStatusOrderByCreatedAtDesc(
            java.util.Collection<UUID> skillIds, String status, Pageable pageable);

    List<AutoInsight> findBySkillIdInAndSeverityAndStatusOrderByCreatedAtDesc(
            java.util.Collection<UUID> skillIds, String severity, String status, Pageable pageable);
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
    @Query("SELECT ai.severity, COUNT(ai) FROM AutoInsight ai WHERE ai.status = 'active' GROUP BY ai.severity")
    List<Object[]> countActiveBySeverity();
    /**
     * 统计（Active,Severity,For,Skill）。
     * @param skillId skillId
     * @return 结果列表
     */
    @Query("SELECT ai.severity, COUNT(ai) FROM AutoInsight ai WHERE ai.skillId = :skillId AND ai.status = 'active' GROUP BY ai.severity")
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

    // ===== 分页版本（列表带总数，避免前端只能拿到一页却不知道有多少） =====

    org.springframework.data.domain.Page<AutoInsight> findBySkillIdAndStatus(
            UUID skillId, String status, Pageable pageable);

    org.springframework.data.domain.Page<AutoInsight> findBySkillIdAndSeverityAndStatus(
            UUID skillId, String severity, String status, Pageable pageable);

    org.springframework.data.domain.Page<AutoInsight> findBySkillIdInAndStatus(
            java.util.Collection<UUID> skillIds, String status, Pageable pageable);

    org.springframework.data.domain.Page<AutoInsight> findBySkillIdInAndSeverityAndStatus(
            java.util.Collection<UUID> skillIds, String severity, String status, Pageable pageable);

    org.springframework.data.domain.Page<AutoInsight> findBySeverityAndStatus(
            String severity, String status, Pageable pageable);

    org.springframework.data.domain.Page<AutoInsight> findByStatus(
            String status, Pageable pageable);

}
