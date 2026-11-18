package com.aiextract.repository;

import com.aiextract.model.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    Optional<WorkflowDefinition> findFirstByWorkflowKeyAndStatusOrderByVersionDesc(
            String workflowKey, String status);

    boolean existsByWorkflowKeyAndVersion(String workflowKey, Integer version);
}
