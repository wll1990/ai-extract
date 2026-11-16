package com.aiextract.repository;

import com.aiextract.model.GrainCorrectionSuggestion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * 颗粒纠错建议数据访问。
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
@Repository
public interface GrainCorrectionSuggestionRepository extends JpaRepository<GrainCorrectionSuggestion, UUID> {

    Page<GrainCorrectionSuggestion> findByCompanyIdAndStatusOrderByCreatedAtDesc(
            UUID companyId, String status, Pageable pageable);

    Page<GrainCorrectionSuggestion> findByCompanyIdOrderByCreatedAtDesc(UUID companyId, Pageable pageable);

    Page<GrainCorrectionSuggestion> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    Page<GrainCorrectionSuggestion> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 同一员工对同一颗粒只保留一条待审建议，避免刷屏。 */
    Optional<GrainCorrectionSuggestion> findFirstByGrainIdAndSuggestedByAndStatusOrderByCreatedAtDesc(
            UUID grainId, UUID suggestedBy, String status);

    long countByCompanyIdAndStatus(UUID companyId, String status);
}
