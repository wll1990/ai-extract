package com.aiextract.repository;

import com.aiextract.model.CompanyRegisterCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyRegisterCodeRepository extends JpaRepository<CompanyRegisterCode, UUID> {

    Optional<CompanyRegisterCode> findByCode(String code);

    List<CompanyRegisterCode> findByCompanyIdOrderByCreatedAtDesc(UUID companyId);
}
