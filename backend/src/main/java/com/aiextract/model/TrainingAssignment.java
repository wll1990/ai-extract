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
 * 学员分配实体 —— 一个学员在一个批次里的培训进度总账。
 *
 * <p>四个冗余计数必须与 {@link TrainingTaskRecord} 的状态变更在**同一事务**内增量更新，
 * 且必须带状态迁移守卫（旧状态非终态才计完成，非 passed → passed 才计通过），
 * 否则重考会重复累加并撞上 ck_ta_counters。</p>
 *
 * <p>补训的当前实现：在**同一分配**下为失败任务追加 {@code round_no > 1} 的新进度行
 * （见 TrainingRemediationService），不会新建批次级分配；{@code sourceAssignmentId}
 * 为历史/预留字段，现有写入路径不会赋值。</p>
 *
 * <p>{@code @DynamicUpdate} 是必需的：进度计数由 {@code addCounters} 走 JPQL 批量更新，
 * 不刷新一级缓存；同一事务里若有更早加载的实体随后被保存（如状态流转），
 * 全列 UPDATE 会把刚累加的计数覆盖回旧值（表现为「任务已完成但进度 0/1」）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "training_assignment")
@DynamicUpdate
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingAssignment {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属企业 ID */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** 所属批次 ID */
    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    /** 学员 ID */
    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    /** 补训来源分配 ID（补训=新批次新分配） */
    @Column(name = "source_assignment_id")
    private UUID sourceAssignmentId;

    /** 状态：not_started/in_progress/pending_certification/certified/cancelled（列宽 32，21 字的 pending_certification 放得下） */
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private String status = STATUS_NOT_STARTED;

    /** 任务总数（下发时快照） */
    @Column(name = "total_tasks", nullable = false)
    @Builder.Default
    private Integer totalTasks = 0;

    /** 必修任务数 */
    @Column(name = "required_total", nullable = false)
    @Builder.Default
    private Integer requiredTotal = 0;

    /** 已完成任务数（阅读已确认或尝试已出结论） */
    @Column(name = "completed_tasks", nullable = false)
    @Builder.Default
    private Integer completedTasks = 0;

    /** 必修已通过数（认证判定用） */
    @Column(name = "required_passed", nullable = false)
    @Builder.Default
    private Integer requiredPassed = 0;

    /** 已通过任务数 */
    @Column(name = "passed_tasks", nullable = false)
    @Builder.Default
    private Integer passedTasks = 0;

    /** 分配截止时间 */
    @Column(name = "deadline")
    private LocalDateTime deadline;

    /** 首次延期前的截止（只记录第一次，后续延期不覆盖） */
    @Column(name = "original_deadline")
    private LocalDateTime originalDeadline;

    /** 最近一次延期时间 */
    @Column(name = "deadline_extended_at")
    private LocalDateTime deadlineExtendedAt;

    /** 最近一次延期操作人 */
    @Column(name = "deadline_extended_by")
    private UUID deadlineExtendedBy;

    /** 最近一次延期理由 */
    @Column(name = "deadline_extend_reason", columnDefinition = "TEXT")
    private String deadlineExtendReason;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== 状态常量 ==========

    public static final String STATUS_NOT_STARTED = "not_started";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_PENDING_CERTIFICATION = "pending_certification";
    public static final String STATUS_CERTIFIED = "certified";
    public static final String STATUS_CANCELLED = "cancelled";

    private static final Set<String> STATUSES = Set.of(
            STATUS_NOT_STARTED, STATUS_IN_PROGRESS, STATUS_PENDING_CERTIFICATION,
            STATUS_CERTIFIED, STATUS_CANCELLED);

    /** 判断状态是否合法。 */
    public static boolean isValidStatus(String status) {
        return status != null && STATUSES.contains(status);
    }

    /** 是否还能推进（已认证/已取消不再变更） */
    public boolean isOpen() {
        return !STATUS_CERTIFIED.equals(status) && !STATUS_CANCELLED.equals(status);
    }

    /** 必修是否全部通过 */
    public boolean isRequiredAllPassed() {
        return requiredTotal > 0 && requiredPassed >= requiredTotal;
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
