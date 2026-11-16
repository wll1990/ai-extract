package com.aiextract.repository;

import com.aiextract.model.ReviewMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewMessageRepository extends JpaRepository<ReviewMessage, UUID> {

    List<ReviewMessage> findTop10BySessionIdOrderByCreatedAtDesc(UUID sessionId);

    List<ReviewMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    long countBySessionIdAndRole(UUID sessionId, String role);
}
