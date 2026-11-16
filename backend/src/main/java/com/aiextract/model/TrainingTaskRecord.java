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
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 学员-任务进度行 —— 一学员一任务一行，分配时按冻结版本一次性建齐。
 *
 * <p>统一承载三类任务的进度，避免「我的培训」拼两套表：
 * 阅读靠 {@code acknowledgedAt} 作完成证据，陪练/考试由 {@link TrainingAttempt} 的结论回写。</p>
 *
 * <p>状态机：locked → available → in_progress → pending_review → passed/failed；waived 须人+理由。
 * 「已读」只证明阅读确认，不代表能力达标。</p>
 *
 * <p>{@code @DynamicUpdate} 是必需的：状态推进用 JPQL 批量更新（会同时写 startedAt/finishedAt），
 * 内存中的实体只同步部分字段；全列 UPDATE 会把批量更新写入的时间戳覆盖成 null。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "training_task_record")
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingTaskRecord {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属分配 ID */
    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    /** 任务 ID */
    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    /** 轮次：1=首次；>1=补训/整改轮（原失败行保留） */
    @Column(name = "round_no", nullable = false)
    @Builder.Default
    private Integer roundNo = 1;

    /** 轮次类型：initial / remediation / reopen（管理员要求重做） */
    @Builder.Default
    @Column(name = "round_type", nullable = false, length = 20)
    private String roundType = "initial";

    /** 补训来源记录（round>1 必填） */
    @Column(name = "remediation_of_record_id")
    private UUID remediationOfRecordId;

    /** 补训原因（round>1 必填） */
    @Column(name = "remediation_reason", columnDefinition = "TEXT")
    private String remediationReason;

    /** 布置补训的人 */
    @Column(name = "remediation_created_by")
    private UUID remediationCreatedBy;

    /** 状态：locked/available/in_progress/pending_review/passed/failed/waived */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_LOCKED;

    /** 阅读确认时间（只证明已读） */
    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    /** 豁免人 ID（waived 必填） */
    @Column(name = "waived_by")
    private UUID waivedBy;

    /** 豁免理由（waived 必填） */
    @Column(name = "waived_reason", columnDefinition = "TEXT")
    private String waivedReason;

    /** 开始时间 */
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    /** 结束时间 */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== 状态常量 ==========

    public static final String STATUS_LOCKED = "locked";
    public static final String STATUS_AVAILABLE = "available";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_PENDING_REVIEW = "pending_review";
    public static final String STATUS_PASSED = "passed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_WAIVED = "waived";

    private static final Set<String> STATUSES = Set.of(
            STATUS_LOCKED, STATUS_AVAILABLE, STATUS_IN_PROGRESS,
            STATUS_PENDING_REVIEW, STATUS_PASSED, STATUS_FAILED, STATUS_WAIVED);

    /** 计入 completed_tasks 的状态（终态） */
    private static final Set<String> TERMINAL = Set.of(STATUS_PASSED, STATUS_FAILED, STATUS_WAIVED);

    /** 判断状态是否合法。 */
    public static boolean isValidStatus(String status) {
        return status != null && STATUSES.contains(status);
    }

    /** 是否终态（决定计数是否已在先前事务中累加过） */
    public boolean isTerminal() {
        return TERMINAL.contains(status);
    }

    /** 是否已通过 */
    public boolean isPassed() {
        return STATUS_PASSED.equals(status);
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
