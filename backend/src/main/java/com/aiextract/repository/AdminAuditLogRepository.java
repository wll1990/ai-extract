package com.aiextract.repository;

import com.aiextract.model.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 管理员操作审计数据访问接口 — Phase 3 开始写入。
  * @author AI Extract Team
 */
@Repository
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {
    /**
     * 查询（Admin,Id,Created,At）。
     * @param adminId adminId
     * @return 分页结果
     */
    Page<AdminAuditLog> findByAdminIdOrderByCreatedAtDesc(UUID adminId, Pageable pageable);
    /**
     * 查询（Target,Type,Target,Id,Created,At）。
     * @param targetType targetType
     * @param targetId targetId
     * @return 分页结果
     */
    Page<AdminAuditLog> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, UUID targetId, Pageable pageable);

    /** 保留策略 dry-run：到期记录数 */
    long countByCreatedAtBefore(LocalDateTime cutoff);

    /** DATA-02 归档：单条 SQL 原子搬移一批到期审计记录到归档表。 */
    @Modifying
    @Query(value = "WITH moved AS (" +
            " DELETE FROM public.admin_audit_log WHERE id IN (" +
            "   SELECT id FROM public.admin_audit_log WHERE created_at < :cutoff LIMIT :limit" +
            " ) RETURNING *" +
            ") INSERT INTO public.admin_audit_log_archive" +
            " SELECT moved.*, now() FROM moved ON CONFLICT (id) DO NOTHING", nativeQuery = true)
    int archiveBatch(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

}
