package com.aiextract.repository;

import com.aiextract.model.TrainingTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 培训任务数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface TrainingTaskRepository extends JpaRepository<TrainingTask, UUID> {

    /** 计划某版本的任务树，按阶段、阶段内顺序稳定返回（命中 idx_tt_plan_version_phase） */
    List<TrainingTask> findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(UUID planId, Integer version);

    /** 版本内单个任务（编辑/删除时校验归属） */
    Optional<TrainingTask> findByIdAndPlanIdAndVersion(UUID id, UUID planId, Integer version);

    /** 某阶段下的最大顺序号，新增任务自动递增用 */
    @Query("SELECT COALESCE(MAX(t.orderNo), 0) FROM TrainingTask t "
            + "WHERE t.planId = :planId AND t.version = :version AND t.phaseNo = :phaseNo")
    int findMaxOrderNo(@Param("planId") UUID planId,
                       @Param("version") Integer version,
                       @Param("phaseNo") Integer phaseNo);

    /** 版本内最大阶段号；新任务未指定阶段时沿用最后阶段 */
    @Query("SELECT COALESCE(MAX(t.phaseNo), 0) FROM TrainingTask t "
            + "WHERE t.planId = :planId AND t.version = :version")
    int findMaxPhaseNo(@Param("planId") UUID planId, @Param("version") Integer version);

    /** 是否有培训任务引用该知识/案例（彻底删除前校验） */
    boolean existsByResourceId(UUID resourceId);

    /** 版本内任务总数（发布时写 task_count 快照） */
    long countByPlanIdAndVersion(UUID planId, Integer version);

    /** 版本内必修任务数（分配时写 required_total） */
    @Query("SELECT COUNT(t) FROM TrainingTask t "
            + "WHERE t.planId = :planId AND t.version = :version AND t.isRequired = true")
    long countRequired(@Param("planId") UUID planId, @Param("version") Integer version);
}
