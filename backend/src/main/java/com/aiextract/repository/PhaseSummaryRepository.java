package com.aiextract.repository;

import com.aiextract.model.PhaseSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 访谈阶段摘要 Repository。
 *
 * @author AI Extract Team
 * @since 2026-07-30
 */
public interface PhaseSummaryRepository extends JpaRepository<PhaseSummary, UUID> {

    List<PhaseSummary> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    void deleteBySessionIdAndPhase(UUID sessionId, String phase);
}
