package com.aiextract.repository;

import com.aiextract.model.TrainingPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 培训计划数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface TrainingPlanRepository extends JpaRepository<TrainingPlan, UUID> {

    /** 企业内计划列表，可按状态与关键字过滤（关键字为空时不过滤） */
    @Query("SELECT p FROM TrainingPlan p WHERE p.companyId = :companyId "
            + "AND (:status IS NULL OR p.status = :status) "
            + "AND (:keyword IS NULL OR LOWER(p.name) LIKE :keyword) "
            + "ORDER BY p.createdAt DESC")
    Page<TrainingPlan> search(@Param("companyId") UUID companyId,
                              @Param("status") String status,
                              @Param("keyword") String keyword,
                              Pageable pageable);

    /** 企业隔离读取：不属于本企业视为不存在 */
    Optional<TrainingPlan> findByIdAndCompanyId(UUID id, UUID companyId);

    /** 定时扫描：全平台指定状态计划（仅内部任务使用） */
    List<TrainingPlan> findByStatus(String status);
}
