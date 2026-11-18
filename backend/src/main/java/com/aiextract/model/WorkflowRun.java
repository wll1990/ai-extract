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
@Table(name = "workflow_run")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRun {

    @Id
    private UUID id;

    @Column(name = "definition_id", nullable = false, updatable = false)
    private UUID definitionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "company_id")
    private UUID companyId;

    /** pending|running|waiting|succeeded|failed|canceled */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input", columnDefinition = "jsonb")
    private String input;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output", columnDefinition = "jsonb")
    private String output;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "current_node", length = 64)
    private String currentNode;

    @Column(name = "lease_owner", length = 64)
    private String leaseOwner;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "attempts", nullable = false)
    private Integer attempts;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "resume_token", length = 64)
    private String resumeToken;

    @Column(name = "waiting_until")
    private Instant waitingUntil;

    @Column(name = "event_seq", nullable = false)
    private Integer eventSeq;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
