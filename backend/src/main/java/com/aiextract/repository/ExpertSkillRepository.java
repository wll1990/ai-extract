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
    List<ExpertSkill> findPendingTasks(@Param("timeout") LocalDateTime timeout);
    /**
     * try（Lock）。
     * @param id id
     * @param workerId workerId
     * @return 统计数量
     */
    int tryLock(@Param("id") UUID id, @Param("workerId") String workerId);
    /**
     * release（Lock）。
     * @param id id
     */
    void releaseLock(@Param("id") UUID id);
    /**
     * 查询（Status,Domain）。
     * @param status status
     * @param domain domain
     * @return 结果列表
     */
    List<ExpertSkill> findByStatusAndDomain(String status, String domain);


}
