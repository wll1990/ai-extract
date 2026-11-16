package com.aiextract.repository;

import com.aiextract.model.Lead;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface LeadRepository extends JpaRepository<Lead, UUID> {

    Page<Lead> findByCompanyIdAndStatusOrderByLastActivityAtDesc(
            UUID companyId, String status, Pageable pageable);

    Page<Lead> findByCompanyIdOrderByLastActivityAtDesc(UUID companyId, Pageable pageable);

    Page<Lead> findByCompanyIdAndOwnerIdAndStatusOrderByLastActivityAtDesc(
            UUID companyId, UUID ownerId, String status, Pageable pageable);

    Page<Lead> findByCompanyIdAndOwnerIdOrderByLastActivityAtDesc(
            UUID companyId, UUID ownerId, Pageable pageable);

    @Query(value = "SELECT * FROM lead WHERE company_id=:companyId AND ("
            + " name_normalized=CAST(:normalized AS text) "
            + " OR (:digits IS NOT NULL AND contact_phone_digits=CAST(:digits AS text))"
            + ") ORDER BY created_at DESC LIMIT :limit", nativeQuery = true)
    List<Lead> findDuplicates(@Param("companyId") UUID companyId,
                              @Param("normalized") String normalized,
                              @Param("digits") String digits,
                              @Param("limit") int limit);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Lead l SET l.status=:to, l.updatedAt=:now, l.lastActivityAt=:now "
            + "WHERE l.id=:id AND l.status=:from")
    int updateStatusIfCurrent(@Param("id") UUID id, @Param("from") String from,
                              @Param("to") String to,
                              @Param("now") java.time.LocalDateTime now);

    // ==================== 个人看板（SA-07） ====================

    long countByCompanyIdAndOwnerId(UUID companyId, UUID ownerId);

    long countByCompanyIdAndOwnerIdAndStatus(UUID companyId, UUID ownerId, String status);

    long countByCompanyIdAndOwnerIdAndCreatedAtAfter(
            UUID companyId, UUID ownerId, java.time.LocalDateTime after);

    long countByCompanyIdAndOwnerIdAndStatusAndCreatedAtAfter(
            UUID companyId, UUID ownerId, String status, java.time.LocalDateTime after);

    /** 待跟进：跟进中且（超过 N 天无活动 或 已到下次跟进时间） */
    @Query("SELECT COUNT(l) FROM Lead l WHERE l.companyId=:companyId AND l.ownerId=:ownerId "
            + "AND l.status='following' AND (l.lastActivityAt IS NULL OR l.lastActivityAt < :threshold "
            + "OR (l.nextFollowUpAt IS NOT NULL AND l.nextFollowUpAt <= :now))")
    long countNeedFollowUp(@Param("companyId") UUID companyId, @Param("ownerId") UUID ownerId,
                           @Param("threshold") java.time.LocalDateTime threshold,
                           @Param("now") java.time.LocalDateTime now);

    /** 已过下次跟进时间仍未处理的线索 */
    @Query("SELECT COUNT(l) FROM Lead l WHERE l.companyId=:companyId AND l.ownerId=:ownerId "
            + "AND l.status='following' AND l.nextFollowUpAt IS NOT NULL AND l.nextFollowUpAt < :now")
    long countOverdueFollowUp(@Param("companyId") UUID companyId, @Param("ownerId") UUID ownerId,
                              @Param("now") java.time.LocalDateTime now);

    @Query(value = "SELECT * FROM lead WHERE company_id=:companyId AND id<>:excludeId AND ("
            + " name_normalized=CAST(:normalized AS text) "
            + " OR (:digits IS NOT NULL AND contact_phone_digits=CAST(:digits AS text))"
            + ") ORDER BY created_at DESC LIMIT :limit", nativeQuery = true)
    List<Lead> findDuplicatesExcluding(@Param("companyId") UUID companyId,
                                       @Param("normalized") String normalized,
                                       @Param("digits") String digits,
                                       @Param("excludeId") UUID excludeId,
                                       @Param("limit") int limit);
}
