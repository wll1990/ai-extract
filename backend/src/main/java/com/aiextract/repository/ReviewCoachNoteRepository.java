package com.aiextract.repository;

import com.aiextract.model.ReviewCoachNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReviewCoachNoteRepository extends JpaRepository<ReviewCoachNote, UUID> {

    List<ReviewCoachNote> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);
}
