package com.aiextract.repository;

import com.aiextract.model.WorkflowRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    /**
     * 单 worker 领取：SKIP LOCKED 避免多实例抢同一 run。
     * 必须在事务内调用。
     */
    @Query(value = "SELECT * FROM workflow_run "
            + "WHERE status IN ('pending','running') "
            + "AND available_at <= :now "
            + "AND (lease_until IS NULL OR lease_until < :now) "
            + "ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<WorkflowRun> claimNext(@Param("now") Instant now);
}
