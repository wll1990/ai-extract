package com.aiextract.repository;

import com.aiextract.model.CompanyAiConfigHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CompanyAiConfigHistoryRepository extends JpaRepository<CompanyAiConfigHistory, UUID> {

    List<CompanyAiConfigHistory> findTop20ByCompanyIdOrderByCreatedAtDesc(UUID companyId);
}
