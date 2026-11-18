package com.aiextract.repository;

import com.aiextract.model.WorkflowNodeRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowNodeRunRepository extends JpaRepository<WorkflowNodeRun, UUID> {

    List<WorkflowNodeRun> findByRunIdOrderByStartedAtAsc(UUID runId);
}
