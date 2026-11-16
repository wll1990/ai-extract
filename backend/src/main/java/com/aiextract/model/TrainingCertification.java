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
 * 培训认证实体 —— 一次分配对应一条认证记录。
 *
 * <p>认证不是自动发放：必修任务全部通过后分配进入 pending_certification，
 * 由主管/导师显式确认后才落 confirmed（服务端校验门槛，不信任前端）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "training_certification")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingCertification {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属企业 ID */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** 所属分配 ID（一条分配一条认证） */
    @Column(name = "assignment_id", nullable = false)
    private UUID assignmentId;

    /** 学员 ID */
    @Column(name = "learner_id", nullable = false)
    private UUID learnerId;

    /** 状态：pending/confirmed/remediating */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_PENDING;

    /** 确认人 ID */
    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    /** 确认时间 */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 备注 */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** 撤销人 ID */
    @Column(name = "revoked_by")
    private UUID revokedBy;

    /** 撤销时间 */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** 撤销理由 */
    @Column(name = "revoke_reason", columnDefinition = "TEXT")
    private String revokeReason;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== 状态常量 ==========

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_CONFIRMED = "confirmed";
    public static final String STATUS_REMEDIATING = "remediating";
    public static final String STATUS_REVOKED = "revoked";

    private static final Set<String> STATUSES = Set.of(
            STATUS_PENDING, STATUS_CONFIRMED, STATUS_REMEDIATING, STATUS_REVOKED);

    /** 判断状态是否合法。 */
    public static boolean isValidStatus(String status) {
        return status != null && STATUSES.contains(status);
    }

    /** 是否已确认 */
    public boolean isConfirmed() {
        return STATUS_CONFIRMED.equals(status);
    }

    /** 是否已撤销 */
    public boolean isRevoked() {
        return STATUS_REVOKED.equals(status);
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
