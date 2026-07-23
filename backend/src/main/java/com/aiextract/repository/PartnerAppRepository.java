package com.aiextract.repository;

import com.aiextract.model.PartnerApp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PartnerAppRepository extends JpaRepository<PartnerApp, UUID> {
    Optional<PartnerApp> findByAppId(String appId);
    boolean existsByAppId(String appId);
}
