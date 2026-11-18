package com.aiextract.repository;

import com.aiextract.model.WorkflowEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowEventRepository extends JpaRepository<WorkflowEvent, Long> {

    List<WorkflowEvent> findByRunIdOrderBySeqAsc(UUID runId);
}
