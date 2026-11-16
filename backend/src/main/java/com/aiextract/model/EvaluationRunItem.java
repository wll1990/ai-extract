package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 评测单题执行实体 —— 一题一次尝试一行。
 *
 * <p>三个维度严格分离，不允许用一个字段表达两件事：</p>
 * <ul>
 *   <li>执行态 {@code status}：pending → generating → scoring → completed / error</li>
 *   <li>业务结论 {@code verdict}：pass / fail / undetermined（人工复核可改）</li>
 *   <li>重试 {@code attemptNo}：重试建新行，旧行证据不覆盖</li>
 * </ul>
 *
 * <p>租约三字段（{@code leaseOwner}/{@code leaseExpiresAt}/{@code heartbeatAt}）供 worker
 * 原子领取与进程中断恢复使用；超时未续租的记录可被重新领取。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "evaluation_run_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationRunItem {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属批次 ID */
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    /** 题目 ID */
    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    /** 题目版本快照 */
    @Column(name = "item_version", nullable = false)
    private Integer itemVersion;

    /** 执行态：pending/generating/scoring/completed/error */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_PENDING;

    /** 业务结论：pass/fail/undetermined（与执行态独立） */
    @Column(name = "verdict", length = 20)
    private String verdict;

    /** 分身回答原文 */
    @Column(name = "ai_answer", columnDefinition = "TEXT")
    private String aiAnswer;

    /** 检索命中证据（grain/knowledge/case + 分数） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieval_evidence", columnDefinition = "JSONB")
    private String retrievalEvidence;

    /** 规则检查结果（禁止项/引用要求） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_check_result", columnDefinition = "JSONB")
    private String ruleCheckResult;

    /** LLM 评分原分（含 confidence） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ai_score", columnDefinition = "JSONB")
    private String aiScore;

    /** 执行错误原因 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 重试次数：同批次同题唯一（run_id, item_id, attempt_no） */
    @Column(name = "attempt_no", nullable = false)
    @Builder.Default
    private Integer attemptNo = 1;

    /** 领取 worker 标识（租约） */
    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    /** 租约到期时间：过期可被重新领取 */
    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    /** 心跳时间：进程中断恢复判据 */
    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== 执行态常量 ==========

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_GENERATING = "generating";
    public static final String STATUS_SCORING = "scoring";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_ERROR = "error";

    // ========== 业务结论常量 ==========

    public static final String VERDICT_PASS = "pass";
    public static final String VERDICT_FAIL = "fail";
    public static final String VERDICT_UNDETERMINED = "undetermined";

    /** 更新前自动设置更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
