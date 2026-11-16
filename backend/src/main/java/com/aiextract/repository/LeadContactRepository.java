package com.aiextract.repository;

import com.aiextract.model.LeadContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeadContactRepository extends JpaRepository<LeadContact, UUID> {

    List<LeadContact> findByCompanyIdAndLeadIdOrderByPrimaryDescCreatedAtAsc(
            UUID companyId, UUID leadId);

    Optional<LeadContact> findByIdAndCompanyIdAndLeadId(UUID id, UUID companyId, UUID leadId);

    @Modifying
    @Query("UPDATE LeadContact c SET c.primary = false, c.updatedAt = :now "
            + "WHERE c.leadId = :leadId AND c.primary = true")
    int clearPrimary(@Param("leadId") UUID leadId, @Param("now") LocalDateTime now);
}
