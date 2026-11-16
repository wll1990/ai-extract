package com.aiextract.repository;

import com.aiextract.model.ReviewSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ReviewSessionRepository extends JpaRepository<ReviewSession, UUID> {

    Page<ReviewSession> findByCompanyIdAndReviewerIdOrderByCreatedAtDesc(
            UUID companyId, UUID reviewerId, Pageable pageable);

    Page<ReviewSession> findByCompanyIdAndReviewerIdAndStatusOrderByCreatedAtDesc(
            UUID companyId, UUID reviewerId, String status, Pageable pageable);

    Page<ReviewSession> findByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    Page<ReviewSession> findByCompanyIdAndStatusOrderByCreatedAtDesc(
            UUID companyId, String status, Pageable pageable);

    Page<ReviewSession> findByCompanyIdAndCoachOwnerIdOrderByCreatedAtDesc(
            UUID companyId, UUID coachOwnerId, Pageable pageable);

    long countByCompanyIdAndCoachOwnerId(UUID companyId, UUID coachOwnerId);

    /** 同客户的上一次复盘（本人），用于跨次对比 */
    Optional<ReviewSession> findFirstByCompanyIdAndLeadIdAndReviewerIdAndIdNotAndCreatedAtBeforeOrderByCreatedAtDesc(
            UUID companyId, UUID leadId, UUID reviewerId, UUID id, LocalDateTime createdAt);

    /** 同客户的上一次复盘（有全公司查看权限时跨人对比） */
    Optional<ReviewSession> findFirstByCompanyIdAndLeadIdAndIdNotAndCreatedAtBeforeOrderByCreatedAtDesc(
            UUID companyId, UUID leadId, UUID id, LocalDateTime createdAt);

    /** 生成报告前锁会话行，串行化双击/并发请求 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ReviewSession s WHERE s.id = :id")
    Optional<ReviewSession> findByIdForUpdate(@Param("id") UUID id);

    // ==================== 个人看板（SA-07） ====================

    long countByCompanyIdAndReviewerId(UUID companyId, UUID reviewerId);

    long countByCompanyIdAndReviewerIdAndCreatedAtAfter(
            UUID companyId, UUID reviewerId, java.time.LocalDateTime after);

    long countByCompanyIdAndReviewerIdAndStatus(
            UUID companyId, UUID reviewerId, String status);

    long countByCompanyIdAndReviewerIdAndStatusAndCreatedAtAfter(
            UUID companyId, UUID reviewerId, String status, java.time.LocalDateTime after);

    /** 最近 N 周复盘量（按周一聚合，升序） */
    @Query(value = "SELECT to_char(date_trunc('week', created_at), 'YYYY-MM-DD') AS week, "
            + "COUNT(*) FROM review_session "
            + "WHERE company_id = :companyId AND reviewer_id = :reviewerId AND created_at >= :since "
            + "GROUP BY week ORDER BY week", nativeQuery = true)
    java.util.List<Object[]> countWeeklyByReviewer(@Param("companyId") UUID companyId,
                                                   @Param("reviewerId") UUID reviewerId,
                                                   @Param("since") java.time.LocalDateTime since);
}
