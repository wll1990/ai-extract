package com.aiextract.repository;

import com.aiextract.model.TrainingCohort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 培训批次数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface TrainingCohortRepository extends JpaRepository<TrainingCohort, UUID> {

    /** 企业内批次列表，可按计划与状态过滤 */
    @Query("SELECT c FROM TrainingCohort c WHERE c.companyId = :companyId "
            + "AND (:planId IS NULL OR c.planId = :planId) "
            + "AND (:status IS NULL OR c.status = :status) "
            + "ORDER BY c.createdAt DESC")
    Page<TrainingCohort> search(@Param("companyId") UUID companyId,
                                @Param("planId") UUID planId,
                                @Param("status") String status,
                                Pageable pageable);

    /** 企业隔离读取 */
    Optional<TrainingCohort> findByIdAndCompanyId(UUID id, UUID companyId);

    /** 导师的「我的批次」：只返回 mentor_id 指向本人的批次 */
    java.util.List<TrainingCohort> findByCompanyIdOrderByCreatedAtDesc(UUID companyId);

    Page<TrainingCohort> findByCompanyIdAndMentorIdOrderByCreatedAtDesc(
            UUID companyId, UUID mentorId, Pageable pageable);

    /**
     * 加行锁读取批次 —— 批量分配时串行化，避免并发批次因唯一约束整批回滚。
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM TrainingCohort c WHERE c.id = :id")
    Optional<TrainingCohort> findByIdForUpdate(@Param("id") UUID id);

    /** 计划下已有批次数量（退役前提示与校验） */
    long countByPlanIdAndCompanyIdAndStatusNot(UUID planId, UUID companyId, String status);

    /**
     * 计划下批次实际绑定的版本号（去重）。
     *
     * <p>资源失效扫描必须按批次冻结的版本进行：新建草稿版本后，已发布版本
     * 仍是学员在学的版本，不能只扫 {@code plan.currentVersion}。</p>
     */
    @Query("SELECT DISTINCT c.planVersion FROM TrainingCohort c WHERE c.planId = :planId")
    java.util.List<Integer> findDistinctPlanVersionsByPlanId(@Param("planId") UUID planId);
}
