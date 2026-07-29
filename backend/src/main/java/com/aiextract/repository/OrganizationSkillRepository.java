package com.aiextract.repository;

import com.aiextract.model.OrganizationSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 组织分身 Repository — 按企业租户查询，支持状态过滤。
 *
 * @author AI Extract Team
 * @since 2026-07-28
 */
@Repository
public interface OrganizationSkillRepository extends JpaRepository<OrganizationSkill, UUID> {

    List<OrganizationSkill> findByCompanyId(UUID companyId);

    List<OrganizationSkill> findByCompanyIdAndStatus(UUID companyId, String status);

    List<OrganizationSkill> findByStatus(String status);

    List<OrganizationSkill> findByStatusIn(List<String> statuses);
}
