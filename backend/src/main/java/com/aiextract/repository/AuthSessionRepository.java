package com.aiextract.repository;

import com.aiextract.model.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    Optional<AuthSession> findByTokenHash(String tokenHash);

    Optional<AuthSession> findByTokenHashAndRevokedAtIsNullAndAbsoluteExpiresAtAfter(
            String tokenHash, Instant now);

    List<AuthSession> findByUserIdAndRevokedAtIsNullAndAbsoluteExpiresAtAfterOrderByCreatedAtDesc(
            UUID userId, Instant now);

    List<AuthSession> findByPartnerAppIdAndExternalUserIdAndRevokedAtIsNullAndAbsoluteExpiresAtAfterOrderByCreatedAtDesc(
            UUID partnerAppId, String externalUserId, Instant now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE AuthSession s SET s.revokedAt = :now, s.revokedReason = :reason "
            + "WHERE s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAllByUserId(@Param("userId") UUID userId,
                          @Param("now") Instant now,
                          @Param("reason") String reason);

    @Modifying(clearAutomatically = true)
    @Query(value = "DELETE FROM auth_session WHERE id IN ("
            + "SELECT id FROM auth_session WHERE absolute_expires_at < :cutoff LIMIT :limit)",
            nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
