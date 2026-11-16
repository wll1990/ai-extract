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

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 培训批次（班级）实体 —— 一次分配活动，绑定计划的冻结版本。
 *
 * <p>{@code mentorId} 是导师/复核人。数据级授权规则：导师只能复核自己带的批次，
 * 企业管理员可复核本企业全部批次（见 TrainingAccessService）。</p>
 *
 * <p>状态：active → completed（结班）/ cancelled / archived。批次不影响分配记录的独立状态。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "training_cohort")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingCohort {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属企业 ID */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** 所属计划 ID */
    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    /** 绑定的冻结版本 */
    @Column(name = "plan_version", nullable = false)
    private Integer planVersion;

    /** 批次名 */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** 导师/复核人 ID */
    @Column(name = "mentor_id")
    private UUID mentorId;

    /** 批次截止时间（逾期是时间派生态，不存状态） */
    @Column(name = "deadline")
    private LocalDateTime deadline;

    /** 状态：active/completed/cancelled/archived */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_ACTIVE;

    /** 创建人 ID */
    @Column(name = "created_by")
    private UUID createdBy;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== 状态常量 ==========

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_ARCHIVED = "archived";

    private static final Set<String> STATUSES = Set.of(
            STATUS_ACTIVE, STATUS_COMPLETED, STATUS_CANCELLED, STATUS_ARCHIVED);

    /** 判断状态是否合法。 */
    public static boolean isValidStatus(String status) {
        return status != null && STATUSES.contains(status);
    }

    /** 是否还能继续分配学员 */
    public boolean isAssignable() {
        return STATUS_ACTIVE.equals(status);
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
