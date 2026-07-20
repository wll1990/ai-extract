package com.aiextract.repository;

import com.aiextract.model.Skill;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Skill数据访问接口
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Repository
public interface SkillRepository extends JpaRepository<Skill, UUID> {

    /**
     * 按空间ID查询Skill
     *
     * @param spaceId 空间ID
     * @return Skill（可能为空）
     */
    Optional<Skill> findBySpaceId(UUID spaceId);
    /**
     * 查询，按，space，id，范围内。
     * @param spaceIds 参数
     * @return 查询结果列表
     */
    List<Skill> findBySpaceIdIn(List<UUID> spaceIds);
    /**
     * 查询（Space,Id）。
     * @param spaceIds spaceIds
     * @return 分页结果
     */
    Page<Skill> findBySpaceIdIn(List<UUID> spaceIds, Pageable pageable);
    /**
     * 查询（Space,Id,Status,Created,At）。
     * @param spaceIds spaceIds
     * @param status status
     * @return 分页结果
     */
    Page<Skill> findBySpaceIdInAndStatusOrderByCreatedAtDesc(List<UUID> spaceIds, String status, Pageable pageable);
    /**
     * 查询（Status,Created,At）。
     * @param status status
     * @return 分页结果
     */
    Page<Skill> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    /**
     * 查询（Status）。
     * @param status status
     * @return 结果列表
     */
    List<Skill> findByStatus(String status);
    /**
     * 查询（Display,Name）。
     * @param displayName displayName
     * @return 可能为空的查询结果
     */
    Optional<Skill> findByDisplayName(String displayName);
    /** 补偿扫描：查所有 reviewing 状态、有活跃颗粒但无报告的 skill */
    @Query("SELECT DISTINCT s.id FROM Skill s " +
           "WHERE s.status = 'reviewing' " +
           "AND EXISTS (SELECT 1 FROM ExperienceGrain g WHERE g.spaceId = s.spaceId AND g.status = 'active') " +
           "AND NOT EXISTS (SELECT 1 FROM Report r WHERE r.spaceId = s.spaceId)")
    /**
     * 查询（Reviewing,Skills,Missing,Report）。
     * @return 结果列表
     */
    List<UUID> findReviewingSkillsMissingReport();


}
