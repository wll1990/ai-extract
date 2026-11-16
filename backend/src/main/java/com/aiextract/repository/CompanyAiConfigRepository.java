package com.aiextract.repository;

import com.aiextract.model.CompanyAiConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CompanyAiConfigRepository extends JpaRepository<CompanyAiConfig, UUID> {

    Optional<CompanyAiConfig> findByCompanyIdAndProvider(UUID companyId, String provider);

    /** 一个企业只保留一份对话模型配置（V75 起有唯一约束兜底） */
    Optional<CompanyAiConfig> findFirstByCompanyIdOrderByUpdatedAtDesc(UUID companyId);
}
