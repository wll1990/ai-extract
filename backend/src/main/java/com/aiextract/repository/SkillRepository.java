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

    /** 按 space 列表 + 类型分页 */
    Page<Skill> findBySpaceIdInAndType(List<UUID> spaceIds, String type, Pageable pageable);
    /** 按 space 列表 + 类型 + 状态分页 */
    Page<Skill> findBySpaceIdInAndTypeAndStatusOrderByCreatedAtDesc(List<UUID> spaceIds, String type, String status, Pageable pageable);
    /** 按类型 + 状态分页（super_admin 全量） */
    Page<Skill> findByTypeAndStatusOrderByCreatedAtDesc(String type, String status, Pageable pageable);
    /** 按类型分页（super_admin 全量，无状态过滤） */
    Page<Skill> findByType(String type, Pageable pageable);

    /** 按状态 + 空间列表查询（非分页） */
    List<Skill> findByStatusAndSpaceIdIn(String status, List<UUID> spaceIds);
    /** 按状态列表 + 空间列表查询（非分页） */
    List<Skill> findByStatusInAndSpaceIdIn(List<String> statuses, List<UUID> spaceIds);
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
    /** 按ID列表和状态查询 — 企业数据范围过滤 */
    List<Skill> findByIdInAndStatus(List<UUID> ids, String status);
    /** 按状态列表批量查询 — SkillStatsScheduler 用 */
    List<Skill> findByStatusIn(List<String> statuses);
    /** 按类型+状态列表查询 — 组织分身统计 */
    List<Skill> findByTypeAndStatusIn(String type, List<String> statuses);
    /** 按企业ID查询 — 企业数据范围 */
    List<Skill> findByCompanyId(UUID companyId);
    Page<Skill> findByCompanyId(UUID companyId, Pageable pageable);
    List<Skill> findByCompanyIdAndStatus(UUID companyId, String status);
    Page<Skill> findByCompanyIdAndStatus(UUID companyId, String status, Pageable pageable);
    /** 按企业+类型查询 */
    Page<Skill> findByCompanyIdAndType(UUID companyId, String type, Pageable pageable);
    /** 按类型+状态查询 */
    List<Skill> findByTypeAndStatus(String type, String status);
    /** 公开分页: 已发布+已分享+可选类型过滤，DB 层分页 */
    @Query("SELECT s FROM Skill s WHERE s.status = 'published' "
         + "AND s.id IN :sharedIds "
         + "AND (:type = '' OR s.type = :type)")
    Page<Skill> findPublishedShared(@org.springframework.data.repository.query.Param("sharedIds") List<UUID> sharedIds,
                                     @org.springframework.data.repository.query.Param("type") String type,
                                     Pageable pageable);
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
