package com.aiextract.repository;

import com.aiextract.model.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 企业数据访问接口
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Repository
public interface CompanyRepository extends JpaRepository<Company, UUID> {
}
