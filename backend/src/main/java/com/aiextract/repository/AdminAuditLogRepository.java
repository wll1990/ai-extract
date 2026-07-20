package com.aiextract.repository;

import com.aiextract.model.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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


}
