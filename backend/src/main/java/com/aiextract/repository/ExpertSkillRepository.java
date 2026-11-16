package com.aiextract.repository;

import com.aiextract.model.ExpertSkill;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 萃取师Skill数据访问接口
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Repository
public interface ExpertSkillRepository extends JpaRepository<ExpertSkill, UUID> {

    /** 后台任务健康检查：最近窗口内失败萃取师数 */
    long countByStatusAndUpdatedAtAfter(String status, LocalDateTime after);

    /**
     * 查询By Status。
     * @param status 参数
     * @return 结果列表
     */
    List<ExpertSkill> findByStatus(String status);
    /**
     * 查询By Status。
     * @param status 参数
     * @return 分页结果
     */
    Page<ExpertSkill> findByStatus(String status, Pageable pageable);
    /**
     * 查询（Name,Containing,Ignore,Case）。
     * @param keyword keyword
     * @return 分页结果
     */
    Page<ExpertSkill> findByNameContainingIgnoreCase(String keyword, Pageable pageable);
    /**
     * 查询（Pending,Tasks）。
     * @param timeout timeout
     * @return 结果列表
     */
    @Query("SELECT es FROM ExpertSkill es WHERE (es.status = 'pending' "
            + "OR (es.status = 'analyzing' AND es.lockedAt < :timeout)) "
            + "AND (es.lockedAt IS NULL OR es.lockedAt < :timeout)")
    List<ExpertSkill> findPendingTasks(@Param("timeout") LocalDateTime timeout);

    /** 回收超时未释放的锁，避免 worker 崩溃后任务永久卡在 analyzing */
    @Modifying
    @Query("UPDATE ExpertSkill es SET es.lockedBy = NULL, es.lockedAt = NULL "
            + "WHERE es.lockedBy IS NOT NULL AND es.lockedAt < :timeout")
    int releaseStaleLocks(@Param("timeout") LocalDateTime timeout);
    /**
     * try（Lock）。
     * @param id id
     * @param workerId workerId
     * @return 统计数量
     */
    @Modifying
    @Query("UPDATE ExpertSkill es SET es.lockedBy = :workerId, es.lockedAt = CURRENT_TIMESTAMP WHERE es.id = :id AND es.lockedBy IS NULL")
    int tryLock(@Param("id") UUID id, @Param("workerId") String workerId);
    /**
     * release（Lock）。
     * @param id id
     */
    @Modifying
    @Query("UPDATE ExpertSkill es SET es.lockedBy = NULL, es.lockedAt = NULL WHERE es.id = :id")
    void releaseLock(@Param("id") UUID id);
    /**
     * 查询（Status,Domain）。
     * @param status status
     * @param domain domain
     * @return 结果列表
     */
    List<ExpertSkill> findByStatusAndDomain(String status, String domain);

    // ==================== 企业隔离查询 ====================

    /** 企业内分页列表（company_id 精确匹配，走 idx_expert_skill_company_status） */
    Page<ExpertSkill> findByCompanyId(UUID companyId, Pageable pageable);

    Page<ExpertSkill> findByCompanyIdAndStatus(UUID companyId, String status, Pageable pageable);

    Page<ExpertSkill> findByCompanyIdAndNameContainingIgnoreCase(
            UUID companyId, String keyword, Pageable pageable);

    /** 企业内可用萃取师 */
    List<ExpertSkill> findByCompanyIdAndStatus(UUID companyId, String status);

    List<ExpertSkill> findByCompanyIdAndStatusAndDomain(UUID companyId, String status, String domain);

}
