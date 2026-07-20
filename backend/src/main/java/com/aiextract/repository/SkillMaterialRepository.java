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
    /**
     * 查询（Pending,Parse,Tasks）。
     * @param timeout timeout
     * @return 结果列表
     */
    List<SkillMaterial> findPendingParseTasks(@Param("timeout") LocalDateTime timeout);
    /**
     * 查询（Pending,Cleaning,Tasks）。
     * @param timeout timeout
     * @return 结果列表
     */
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
    int tryLock(@Param("id") UUID id, @Param("workerId") String workerId);
    /**
     * release（Lock）。
     * @param id id
     */
    void releaseLock(@Param("id") UUID id);


}
