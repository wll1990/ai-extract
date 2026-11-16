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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 评测运行批次实体 —— 一次「题集冻结版本 × 分身」的执行。
 *
 * <p>执行态 {@code status} 与审核态 {@code reviewStatus} 分离：跑完不等于通过，
 * 复核完成也不代表执行成功，两者互不覆盖。</p>
 *
 * <p>状态机（执行）：queued → running → completed / partial_failed / failed；
 * 取消走 cancel_requested → cancelled。
 * 状态机（审核）：not_ready → pending_review → finalized。</p>
 *
 * <p>单题证据在两表：本表只存批次计数与聚合分；单题回答/检索证据/评分在
 * {@link EvaluationRunItem}，人工改分在 {@link EvaluationReview}。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "evaluation_run")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationRun {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属企业 ID（企业隔离，不可为空） */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** 题集 ID */
    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    /** 绑定的冻结版本号 */
    @Column(name = "dataset_version", nullable = false)
    private Integer datasetVersion;

    /** 被评测分身 ID */
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    /** 模型/温度等配置快照（可复现性） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "model_config", columnDefinition = "JSONB")
    private String modelConfig;

    /** 分身画像/提示词快照（可复现性） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skill_snapshot", columnDefinition = "JSONB")
    private String skillSnapshot;

    /** 用途：regression/acceptance（沿用 {@link EvaluationDataset} 的用途常量） */
    @Column(name = "purpose", nullable = false, length = 20)
    private String purpose;

    /** 执行态：queued/running/completed/partial_failed/failed/cancel_requested/cancelled */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_QUEUED;

    /** 审核态：not_ready/pending_review/finalized */
    @Column(name = "review_status", nullable = false, length = 20)
    @Builder.Default
    private String reviewStatus = REVIEW_NOT_READY;

    /** 开始执行时间：worker 首次领取时写入，报告耗时起点 */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** 结束时间：批次进入终态时写入，报告耗时终点 */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 总题数 */
    @Column(name = "total_items", nullable = false)
    @Builder.Default
    private Integer totalItems = 0;

    /** 执行完成题数 */
    @Column(name = "completed_items", nullable = false)
    @Builder.Default
    private Integer completedItems = 0;

    /** 执行错误题数 */
    @Column(name = "error_items", nullable = false)
    @Builder.Default
    private Integer errorItems = 0;

    /** 通过题数 */
    @Column(name = "passed_items", nullable = false)
    @Builder.Default
    private Integer passedItems = 0;

    /** 未通过题数 */
    @Column(name = "failed_items", nullable = false)
    @Builder.Default
    private Integer failedItems = 0;

    /** 未判定题数（人工标记 undetermined） */
    @Column(name = "undetermined_items", nullable = false)
    @Builder.Default
    private Integer undeterminedItems = 0;

    /** 平均分 */
    @Column(name = "score_avg", precision = 6, scale = 2)
    private BigDecimal scoreAvg;

    /** 聚合分明细 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "score_detail", columnDefinition = "JSONB")
    private String scoreDetail;

    /** 评测消耗 token（单独计量，不计入业务统计） */
    @Column(name = "usage_tokens", nullable = false)
    @Builder.Default
    private Long usageTokens = 0L;

    /** 评测消耗费用 */
    @Column(name = "usage_cost", nullable = false, precision = 12, scale = 6)
    @Builder.Default
    private BigDecimal usageCost = BigDecimal.ZERO;

    /** 幂等键：同企业内唯一，防前端双击/重放建重复批次 */
    @Column(name = "idempotency_key", length = 100)
    private String idempotencyKey;

    /** 创建人 ID */
    @Column(name = "created_by")
    private UUID createdBy;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== 执行态常量 ==========

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_PARTIAL_FAILED = "partial_failed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCEL_REQUESTED = "cancel_requested";
    public static final String STATUS_CANCELLED = "cancelled";

    // ========== 审核态常量 ==========

    public static final String REVIEW_NOT_READY = "not_ready";
    public static final String REVIEW_PENDING = "pending_review";
    public static final String REVIEW_FINALIZED = "finalized";

    /** 执行中的状态：可接收 worker 领题、可被取消 */
    public static boolean isExecuting(String status) {
        return STATUS_QUEUED.equals(status) || STATUS_RUNNING.equals(status);
    }

    /** 更新前自动设置更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
