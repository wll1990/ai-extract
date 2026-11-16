package com.aiextract.repository;

import com.aiextract.model.FollowUpTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface FollowUpTaskRepository extends JpaRepository<FollowUpTask, UUID> {

    Page<FollowUpTask> findByCompanyIdAndOwnerIdAndStatusOrderByDueAtAsc(
            UUID companyId, UUID ownerId, String status, Pageable pageable);

    Page<FollowUpTask> findByCompanyIdAndOwnerIdOrderByDueAtAsc(
            UUID companyId, UUID ownerId, Pageable pageable);

    Page<FollowUpTask> findByCompanyIdAndStatusOrderByDueAtAsc(
            UUID companyId, String status, Pageable pageable);

    Page<FollowUpTask> findByCompanyIdOrderByDueAtAsc(UUID companyId, Pageable pageable);

    long countByReviewId(UUID reviewId);

    List<FollowUpTask> findByReviewIdOrderByCreatedAtAsc(UUID reviewId);

    List<FollowUpTask> findByReviewIdAndLeadIdIsNull(UUID reviewId);

    /** 线索下一条待处理任务的截止时间，任务收口后回写 lead.next_follow_up_at */
    @Query("SELECT MIN(t.dueAt) FROM FollowUpTask t "
            + "WHERE t.leadId = :leadId AND t.status = 'pending' AND t.dueAt IS NOT NULL")
    LocalDateTime findNextPendingDueAt(@Param("leadId") UUID leadId);

    boolean existsByReviewIdAndTitle(UUID reviewId, String title);

    boolean existsByReviewIdAndTitleAndIdNot(UUID reviewId, String title, UUID id);

    // ==================== 个人看板（SA-07） ====================

    long countByCompanyIdAndOwnerIdAndStatus(UUID companyId, UUID ownerId, String status);

    long countByCompanyIdAndOwnerIdAndStatusAndDueAtBefore(
            UUID companyId, UUID ownerId, String status, LocalDateTime time);

    long countByCompanyIdAndOwnerIdAndStatusAndDueAtBetween(
            UUID companyId, UUID ownerId, String status, LocalDateTime from, LocalDateTime to);

    long countByCompanyIdAndOwnerIdAndStatusAndCompletedAtAfter(
            UUID companyId, UUID ownerId, String status, LocalDateTime after);

    @Query(value = "SELECT * FROM follow_up_task WHERE company_id=:companyId AND lead_id=:leadId "
            + "ORDER BY due_at DESC NULLS LAST, created_at DESC LIMIT 50", nativeQuery = true)
    List<FollowUpTask> findTop50ByCompanyIdAndLeadIdOrderByDueAtDesc(
            @Param("companyId") UUID companyId, @Param("leadId") UUID leadId);

    // ==================== 提醒扫描 ====================

    @Query(value = """
            SELECT id FROM follow_up_task
             WHERE status = 'pending' AND due_at IS NOT NULL
               AND due_at > :now AND due_at <= :deadline
               AND due_reminded_at IS NULL
             ORDER BY due_at ASC LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findDueSoonIds(@Param("now") LocalDateTime now,
                              @Param("deadline") LocalDateTime deadline,
                              @Param("limit") int limit);

    @Query(value = """
            SELECT id FROM follow_up_task
             WHERE status = 'pending' AND due_at IS NOT NULL AND due_at < :now
               AND overdue_notified_at IS NULL
             ORDER BY due_at ASC LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findOverdueIds(@Param("now") LocalDateTime now,
                              @Param("limit") int limit);

    @Query(value = """
            SELECT id FROM follow_up_task
             WHERE status = 'pending' AND due_at IS NOT NULL AND due_at < :threshold
               AND escalated_at IS NULL
             ORDER BY due_at ASC LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findEscalationCandidates(@Param("threshold") LocalDateTime threshold,
                                        @Param("limit") int limit);

    @Modifying
    @Query("UPDATE FollowUpTask t SET t.dueRemindedAt = :now, t.updatedAt = :now "
            + "WHERE t.id = :id AND t.dueRemindedAt IS NULL AND t.status = 'pending'")
    int markDueReminded(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE FollowUpTask t SET t.overdueNotifiedAt = :now, t.updatedAt = :now "
            + "WHERE t.id = :id AND t.overdueNotifiedAt IS NULL AND t.status = 'pending'")
    int markOverdueNotified(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE FollowUpTask t SET t.escalatedAt = :now, t.updatedAt = :now "
            + "WHERE t.id = :id AND t.escalatedAt IS NULL AND t.status = 'pending'")
    int markAutoEscalated(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** 通知失败时回滚提醒标志，让下一轮调度重试（P1-9）。 */
    @Modifying
    @Query("UPDATE FollowUpTask t SET t.dueRemindedAt = NULL, t.updatedAt = :now WHERE t.id = :id")
    int resetDueReminded(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE FollowUpTask t SET t.overdueNotifiedAt = NULL, t.updatedAt = :now WHERE t.id = :id")
    int resetOverdueNotified(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE FollowUpTask t SET t.escalatedAt = NULL, t.updatedAt = :now WHERE t.id = :id")
    int resetAutoEscalated(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
