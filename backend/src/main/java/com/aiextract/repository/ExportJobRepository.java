package com.aiextract.repository;

import com.aiextract.model.ExportJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 导出任务数据访问接口（评测/培训共用）。
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface ExportJobRepository extends JpaRepository<ExportJob, UUID> {

    /** 企业隔离读取（下载前必须校验） */
    Optional<ExportJob> findByIdAndCompanyId(UUID id, UUID companyId);

    /** 企业 + 来源隔离读取：培训导出不能读到/下载到评测导出 */
    Optional<ExportJob> findByIdAndCompanyIdAndSourceType(UUID id, UUID companyId, String sourceType);

    /** 企业导出历史，倒序 */
    Page<ExportJob> findByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    /** 企业 + 来源的导出历史（培训列表只应看到培训导出） */
    Page<ExportJob> findByCompanyIdAndSourceTypeOrderByCreatedAtDesc(
            UUID companyId, String sourceType, Pageable pageable);

    /** 可领取任务：running 且租约为空/过期（FOR UPDATE SKIP LOCKED 防多实例重复领取） */
    @Query(value = "SELECT id FROM export_job WHERE status='running' "
            + "AND source_type='training_cohort' "
            + "AND (lease_expires_at IS NULL OR lease_expires_at < now()) "
            + "ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<UUID> lockClaimableIds(@Param("limit") int limit);

    @Modifying
    @Query(value = "UPDATE export_job SET lease_owner=:owner, lease_expires_at=:expires, "
            + "attempts=attempts+1, updated_at=now() WHERE id=:id AND status='running' "
            + "AND (lease_expires_at IS NULL OR lease_expires_at < now())", nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("owner") String owner,
              @Param("expires") LocalDateTime expires);

    @Modifying
    @Query(value = "UPDATE export_job SET status='done', file_path=:filePath, row_count=:rowCount, "
            + "finished_at=now(), lease_owner=NULL, lease_expires_at=NULL, updated_at=now() "
            + "WHERE id=:id AND lease_owner=:owner AND status='running'", nativeQuery = true)
    int complete(@Param("id") UUID id, @Param("owner") String owner,
                 @Param("filePath") String filePath, @Param("rowCount") int rowCount);

    @Modifying
    @Query(value = "UPDATE export_job SET status=:status, error_message=:error, "
            + "finished_at=CASE WHEN :status='failed' THEN now() ELSE NULL END, "
            + "lease_owner=NULL, lease_expires_at=NULL, updated_at=now() "
            + "WHERE id=:id AND lease_owner=:owner AND status='running'", nativeQuery = true)
    int failOrRequeue(@Param("id") UUID id, @Param("owner") String owner,
                      @Param("status") String status, @Param("error") String error);
}
