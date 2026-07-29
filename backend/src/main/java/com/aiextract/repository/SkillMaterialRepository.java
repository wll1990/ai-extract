package com.aiextract.repository;

import com.aiextract.model.SkillMaterial;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * @author AI Extract Team
 */
public interface SkillMaterialRepository extends JpaRepository<SkillMaterial, UUID> {
    /**
     * 查询，按，skill，id，排序，按，created，at，降序。
     * @param skillId 参数
     * @return 分页结果
     */
    Page<SkillMaterial> findBySkillIdOrderByCreatedAtDesc(UUID skillId, Pageable pageable);
    /**
     * 查询（Skill,Id,Status）。
     * @param skillId skillId
     * @param status status
     * @return 结果列表
     */
    List<SkillMaterial> findBySkillIdAndStatus(UUID skillId, String status);
    /**
     * 查询（Skill,Id）。
     * @param skillId skillId
     * @return 结果列表
     */
    List<SkillMaterial> findBySkillId(UUID skillId);
    /**
     * 查询（Status）。
     * @param statuses statuses
     * @return 结果列表
     */
    List<SkillMaterial> findByStatusIn(List<String> statuses);

    /** 批量：指定状态 + 指定分身列表 */
    List<SkillMaterial> findByStatusInAndSkillIdIn(List<String> statuses, List<UUID> skillIds);

    /** 批量：指定分身列表 */
    List<SkillMaterial> findBySkillIdIn(List<UUID> skillIds);

    /** 批量计数：指定分身列表 */
    @Query("SELECT COUNT(sm) FROM SkillMaterial sm WHERE sm.skillId IN :skillIds")
    long countBySkillIdIn(@Param("skillIds") List<UUID> skillIds);

    /** 素材管道漏斗 — 按 status 分组计数 */
    @Query("SELECT sm.status, COUNT(sm) FROM SkillMaterial sm WHERE sm.skillId IN :skillIds GROUP BY sm.status")
    List<Object[]> pipelineFunnel(@Param("skillIds") List<UUID> skillIds);

    /**
     * 查询（Pending,Parse,Tasks）。
     * @param timeout timeout
     * @return 结果列表
     */
    @Query("SELECT sm FROM SkillMaterial sm WHERE sm.status IN ('uploaded', 'parse_failed') AND (sm.lockedAt IS NULL OR sm.lockedAt < :timeout)")
    List<SkillMaterial> findPendingParseTasks(@Param("timeout") LocalDateTime timeout);
    /**
     * 查询（Pending,Cleaning,Tasks）。
     * @param timeout timeout
     * @return 结果列表
     */
    @Query("SELECT sm FROM SkillMaterial sm WHERE sm.status IN ('parsed', 'cleaning_failed') AND (sm.lockedAt IS NULL OR sm.lockedAt < :timeout)")
    List<SkillMaterial> findPendingCleaningTasks(@Param("timeout") LocalDateTime timeout);
    /**
     * 查询（File,Url）。
     * @param fileUrl fileUrl
     * @return 可能为空的查询结果
     */
    Optional<SkillMaterial> findByFileUrl(String fileUrl);
    /**
     * try（Lock）。
     * @param id id
     * @param workerId workerId
     * @return 统计数量
     */
    @Modifying
    @Query("UPDATE SkillMaterial sm SET sm.lockedBy = :workerId, sm.lockedAt = CURRENT_TIMESTAMP WHERE sm.id = :id AND sm.lockedBy IS NULL")
    int tryLock(@Param("id") UUID id, @Param("workerId") String workerId);
    /**
     * release（Lock）。
     * @param id id
     */
    @Modifying
    @Query("UPDATE SkillMaterial sm SET sm.lockedBy = NULL, sm.lockedAt = NULL WHERE sm.id = :id")
    void releaseLock(@Param("id") UUID id);


}
