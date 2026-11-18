package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_definition")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowDefinition {

    @Id
    private UUID id;

    @Column(name = "workflow_key", nullable = false, length = 64)
    private String workflowKey;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "graph", nullable = false, columnDefinition = "jsonb")
    private String graph;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
