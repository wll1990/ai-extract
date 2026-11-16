package com.aiextract.repository;

import com.aiextract.model.ReviewReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewReportRepository extends JpaRepository<ReviewReport, UUID> {

    Optional<ReviewReport> findFirstBySessionIdOrderByVersionDesc(UUID sessionId);

    /** 后台任务健康检查：最近窗口内失败报告数 */
    long countByStatusAndUpdatedAtAfter(String status, LocalDateTime after);

    List<ReviewReport> findBySessionIdOrderByVersionDesc(UUID sessionId);

    List<ReviewReport> findBySessionIdInOrderBySessionIdAscVersionDesc(Collection<UUID> sessionIds);

    /** 团队共性问题聚合：窗口内已完成的报告，按生成时间倒序，分页限制扫描量 */
    org.springframework.data.domain.Page<ReviewReport> findByCompanyIdAndStatusAndGeneratedAtAfter(
            UUID companyId, String status, LocalDateTime after,
            org.springframework.data.domain.Pageable pageable);

    /** 每个会话只取最高版本，避免列表把全部历史版本 payload 都读出来 */
    @Query(value = "SELECT DISTINCT ON (session_id) * FROM review_report "
            + "WHERE session_id IN (:sessionIds) ORDER BY session_id, version DESC", nativeQuery = true)
    List<ReviewReport> findLatestBySessionIds(@Param("sessionIds") Collection<UUID> sessionIds);

    Optional<ReviewReport> findFirstBySessionIdAndStatusInOrderByVersionDesc(
            UUID sessionId, Collection<String> statuses);

    @Query("SELECT COALESCE(MAX(r.version),0) FROM ReviewReport r WHERE r.sessionId=:sessionId")
    int findMaxVersion(@Param("sessionId") UUID sessionId);

    @Query(value = "SELECT id FROM review_report WHERE (status='queued' "
            + "OR (status='generating' AND lease_expires_at < now())) "
            + "AND attempts < :maxAttempts "
            + "ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<UUID> lockClaimableIds(@Param("limit") int limit, @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query(value = "UPDATE review_report SET status='generating', lease_owner=:owner, "
            + "lease_expires_at=:expires, attempts=attempts+1, "
            + "started_at=COALESCE(started_at, now()), updated_at=now() "
            + "WHERE id=:id AND attempts < :maxAttempts "
            + "AND (status='queued' OR (status='generating' AND lease_expires_at < now()))",
            nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("owner") String owner,
              @Param("expires") LocalDateTime expires, @Param("maxAttempts") int maxAttempts);

    @Modifying
    @Query(value = "UPDATE review_report SET status='ready', summary=:summary, "
            + "payload=CAST(:payload AS jsonb), model_name=:model, generated_at=now(), "
            + "error_message=NULL, lease_owner=NULL, lease_expires_at=NULL, updated_at=now() "
            + "WHERE id=:id AND status='generating' AND lease_owner=:owner", nativeQuery = true)
    int complete(@Param("id") UUID id, @Param("owner") String owner,
                 @Param("summary") String summary, @Param("payload") String payload,
                 @Param("model") String model);

    @Modifying
    @Query(value = "UPDATE review_report SET status=:status, error_message=:error, "
            + "lease_owner=NULL, lease_expires_at=NULL, updated_at=now() "
            + "WHERE id=:id AND status='generating' AND lease_owner=:owner", nativeQuery = true)
    int finishFailed(@Param("id") UUID id, @Param("owner") String owner,
                     @Param("status") String status, @Param("error") String error);

    /** 租约过期且重试次数耗尽、但仍停在 generating 的报告 —— 永远不会再被领取。 */
    @Query(value = "SELECT id FROM review_report WHERE status='generating' "
            + "AND lease_expires_at < now() AND attempts >= :maxAttempts "
            + "ORDER BY updated_at LIMIT :limit", nativeQuery = true)
    List<UUID> findStaleExhaustedIds(@Param("maxAttempts") int maxAttempts,
                                     @Param("limit") int limit);

    @Modifying
    @Query(value = "UPDATE review_report SET status='failed', "
            + "error_message=COALESCE(error_message, '生成超时：重试次数已耗尽'), "
            + "lease_owner=NULL, lease_expires_at=NULL, updated_at=now() "
            + "WHERE id=:id AND status='generating' AND lease_expires_at < now() "
            + "AND attempts >= :maxAttempts", nativeQuery = true)
    int expireStale(@Param("id") UUID id, @Param("maxAttempts") int maxAttempts);
}
