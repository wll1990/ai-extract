package com.aiextract.repository;

import com.aiextract.model.ReviewCoachRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReviewCoachRequestRepository extends JpaRepository<ReviewCoachRequest, UUID> {

    Optional<ReviewCoachRequest> findBySessionId(UUID sessionId);

    Page<ReviewCoachRequest> findByCompanyIdAndCoachUserIdAndStatusOrderByRequestedAtDesc(
            UUID companyId, UUID coachUserId, String status, Pageable pageable);

    Page<ReviewCoachRequest> findByCompanyIdAndCoachUserIdOrderByRequestedAtDesc(
            UUID companyId, UUID coachUserId, Pageable pageable);

    /** 管理员视角：全企业请求 */
    Page<ReviewCoachRequest> findByCompanyIdOrderByRequestedAtDesc(UUID companyId, Pageable pageable);

    Page<ReviewCoachRequest> findByCompanyIdAndStatusOrderByRequestedAtDesc(
            UUID companyId, String status, Pageable pageable);

    Optional<ReviewCoachRequest> findByIdAndCompanyId(UUID id, UUID companyId);

    long countByCompanyIdAndCoachUserIdAndStatus(UUID companyId, UUID coachUserId, String status);
}
