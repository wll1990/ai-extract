package com.aiextract.repository;

import com.aiextract.model.LeadActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LeadActivityRepository extends JpaRepository<LeadActivity, UUID> {

    List<LeadActivity> findTop50ByCompanyIdAndLeadIdOrderByOccurredAtDesc(
            UUID companyId, UUID leadId);

    Page<LeadActivity> findByCompanyIdAndLeadIdOrderByOccurredAtDesc(
            UUID companyId, UUID leadId, Pageable pageable);
}
