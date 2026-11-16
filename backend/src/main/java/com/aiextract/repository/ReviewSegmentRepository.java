package com.aiextract.repository;

import com.aiextract.model.ReviewSegment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewSegmentRepository extends JpaRepository<ReviewSegment, UUID> {

    List<ReviewSegment> findBySessionIdOrderBySeqAsc(UUID sessionId);

    void deleteBySessionId(UUID sessionId);
}
