package com.aiextract.repository;

import com.aiextract.model.TrainingCertification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 培训认证数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface TrainingCertificationRepository extends JpaRepository<TrainingCertification, UUID> {

    /** 当前生效认证（部分唯一索引保证最多一条） */
    Optional<TrainingCertification> findByAssignmentIdAndStatus(UUID assignmentId, String status);

    /** 认证历史：新→旧（撤销后重新认证会有多条） */
    List<TrainingCertification> findByAssignmentIdOrderByCreatedAtDesc(UUID assignmentId);

    /** 批量取生效认证（批次看板避免 N+1） */
    List<TrainingCertification> findByAssignmentIdInAndStatus(List<UUID> assignmentIds, String status);

    /** 批量取全部认证历史（新→旧；列表视图取 confirmed 或最新一条） */
    List<TrainingCertification> findByAssignmentIdInOrderByCreatedAtDesc(List<UUID> assignmentIds);

    /** 企业内某状态的认证数（看板统计） */
    long countByCompanyIdAndStatus(UUID companyId, String status);
}
