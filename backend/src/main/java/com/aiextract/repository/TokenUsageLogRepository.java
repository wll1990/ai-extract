package com.aiextract.repository;

import com.aiextract.model.TokenUsageLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TokenUsageLogRepository extends JpaRepository<TokenUsageLog, UUID> {
}
