package com.aiextract.repository;

import com.aiextract.model.EvaluationRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 评测运行批次数据访问接口。
 *
 * <p>所有查询都带 companyId（企业隔离），命中 {@code idx_run_company_status}
 * / {@code idx_run_skill} / {@code uq_run_idempotency}。分页方法按创建时间倒序，
 * 走 {@code idx_run_company_status} 的隐式排序不保证索引；列表页数据量以企业为界，可接受。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface EvaluationRunRepository extends JpaRepository<EvaluationRun, UUID> {

    /** 企业运行历史（无筛选） */
    Page<EvaluationRun> findByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    /** 企业运行历史（按执行态筛选） */
    Page<EvaluationRun> findByCompanyIdAndStatusOrderByCreatedAtDesc(UUID companyId, String status, Pageable pageable);

    /** 某题集的运行历史 */
    Page<EvaluationRun> findByCompanyIdAndDatasetIdOrderByCreatedAtDesc(UUID companyId, UUID datasetId, Pageable pageable);

    /** 某题集 + 执行态筛选 */
    /** 质量基线趋势：按数据集取已完成的批次 */
    java.util.List<EvaluationRun> findByCompanyIdAndDatasetIdAndStatusInOrderByFinishedAtDesc(
            UUID companyId, UUID datasetId, java.util.Collection<String> statuses, Pageable pageable);

    java.util.List<EvaluationRun> findByDatasetIdAndStatusInOrderByFinishedAtDesc(
            UUID datasetId, java.util.Collection<String> statuses, Pageable pageable);

    Page<EvaluationRun> findByCompanyIdAndDatasetIdAndStatusOrderByCreatedAtDesc(
            UUID companyId, UUID datasetId, String status, Pageable pageable);

    /** 按 ID + 企业取批次（企业隔离的详情入口，越权返回空） */
    Optional<EvaluationRun> findByIdAndCompanyId(UUID id, UUID companyId);

    /** 幂等查询：同企业同幂等键只应存在一条 */
    Optional<EvaluationRun> findByCompanyIdAndIdempotencyKey(UUID companyId, String idempotencyKey);

    /** 对比页：一次取两条，企业隔离由 companyId 保证（调用方限制 ids 数量为 2） */
    List<EvaluationRun> findByIdInAndCompanyId(Collection<UUID> ids, UUID companyId);

    // ==================== API（super_admin 视角） ====================

    /** super_admin 列表：不受企业限制 */
    Page<EvaluationRun> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** super_admin 列表 + 状态筛选 */
    Page<EvaluationRun> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    /** 取消请求：在途批次标记为 cancel_requested，由 Worker 收尾为 cancelled */
    @Modifying
    @Query(value = "UPDATE evaluation_run SET status = 'cancel_requested', updated_at = now() "
            + "WHERE id = :id AND status IN ('queued', 'running')", nativeQuery = true)
    int markCancelRequested(@Param("id") UUID id);

    /** 写入聚合明细（定稿时算出的检索指标等） */
    @Modifying
    @Query("UPDATE EvaluationRun r SET r.scoreDetail = :scoreDetail, r.updatedAt = :now WHERE r.id = :id")
    int updateScoreDetail(@Param("id") UUID id, @Param("scoreDetail") String scoreDetail,
                          @Param("now") LocalDateTime now);

    /** 更新审核态（定稿 / 复核后回到待审） */
    @Modifying
    @Query("UPDATE EvaluationRun r SET r.reviewStatus = :reviewStatus, r.updatedAt = :now WHERE r.id = :id")
    int updateReviewStatus(@Param("id") UUID id, @Param("reviewStatus") String reviewStatus,
                           @Param("now") LocalDateTime now);

    /** 重试失败题后重新排队：清掉结束时间，回到 queued */
    @Modifying
    @Query("UPDATE EvaluationRun r SET r.status = 'queued', r.reviewStatus = 'not_ready', "
            + "r.finishedAt = null, r.updatedAt = :now WHERE r.id = :id")
    int requeue(@Param("id") UUID id, @Param("now") LocalDateTime now);

    // ==================== Worker（P1） ====================

    /** 可推进的批次：queued / running（含 cancel_requested，由 Worker 收尾） */
    @Query(value = "SELECT id FROM evaluation_run WHERE status IN ('queued', 'running', 'cancel_requested') "
            + "ORDER BY created_at LIMIT :limit", nativeQuery = true)
    List<UUID> findActiveRunIds(@Param("limit") int limit);

    /** 批次进入执行态，并记录首次开始时间（已存在则不覆盖） */
    @Modifying
    @Query(value = "UPDATE evaluation_run SET status = 'running', "
            + "started_at = COALESCE(started_at, now()), updated_at = now() "
            + "WHERE id = :id AND status IN ('queued', 'running')", nativeQuery = true)
    int markRunning(@Param("id") UUID id);

    /** 批次计数器与平均分写入（同事务内与单题状态一起提交） */
    @Modifying
    @Query("UPDATE EvaluationRun r SET r.totalItems = :total, r.completedItems = :completed, "
            + "r.errorItems = :error, r.passedItems = :passed, r.failedItems = :failed, "
            + "r.undeterminedItems = :undetermined, r.scoreAvg = :scoreAvg, r.updatedAt = :now "
            + "WHERE r.id = :id")
    int updateCounters(@Param("id") UUID id, @Param("total") int total, @Param("completed") int completed,
                       @Param("error") int error, @Param("passed") int passed, @Param("failed") int failed,
                       @Param("undetermined") int undetermined, @Param("scoreAvg") BigDecimal scoreAvg,
                       @Param("now") LocalDateTime now);

    /**
     * 批次进入终态：写状态、审核态与结束时间。
     *
     * <p>条件是 {@code status IN (queued, running)}：收尾只能推进在途批次，
     * 不能把已被取消（cancelled）的批次改回 completed。</p>
     */
    @Modifying
    @Query("UPDATE EvaluationRun r SET r.status = :status, r.reviewStatus = :reviewStatus, "
            + "r.finishedAt = :finishedAt, r.updatedAt = :now "
            + "WHERE r.id = :id AND r.status IN ('queued', 'running')")
    int finish(@Param("id") UUID id, @Param("status") String status,
               @Param("reviewStatus") String reviewStatus,
               @Param("finishedAt") LocalDateTime finishedAt, @Param("now") LocalDateTime now);

    /** 取消收尾：cancel_requested / running / queued → cancelled（历史证据保留） */
    @Modifying
    @Query(value = "UPDATE evaluation_run SET status = 'cancelled', finished_at = now(), updated_at = now() "
            + "WHERE id = :id AND status IN ('queued', 'running', 'cancel_requested')", nativeQuery = true)
    int markCancelled(@Param("id") UUID id);
}
