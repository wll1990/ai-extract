package com.aiextract.repository;

import com.aiextract.model.TrainingPlanVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 培训计划版本数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface TrainingPlanVersionRepository extends JpaRepository<TrainingPlanVersion, UUID> {

    /** 按计划+版本号取版本（发布、分配、校验都走这条，命中 uq_tpv_plan_version） */
    Optional<TrainingPlanVersion> findByPlanIdAndVersion(UUID planId, Integer version);

    /** 版本列表（计划详情页展示版本历史） */
    List<TrainingPlanVersion> findByPlanIdOrderByVersionDesc(UUID planId);

    /** 当前最大版本号，无版本返回 0（新建版本时 +1） */
    @Query("SELECT COALESCE(MAX(v.version), 0) FROM TrainingPlanVersion v WHERE v.planId = :planId")
    int findMaxVersion(@Param("planId") UUID planId);

    /** 最新已发布版本 —— 尚未建批次时资源扫描的回退目标 */
    Optional<TrainingPlanVersion> findFirstByPlanIdAndStatusOrderByVersionDesc(UUID planId, String status);
}
