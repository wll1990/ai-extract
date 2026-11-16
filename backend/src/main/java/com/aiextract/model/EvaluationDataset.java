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
import java.util.UUID;

/**
 * 评测中心题集实体 —— 评测/培训共用的「版本化题集」。
 *
 * <p>三用途：regression（研发回归，测召回）/ acceptance（分身验收）/ training（培训考试）。
 * company_id 为 NULL 表示平台公共模板（企业只能复制，不能直接改）。</p>
 *
 * <p>状态机：draft → in_review → approved → published → retired；退回回 draft。
 * 已发布版本冻结，编辑复制新草稿，不覆盖已运行/已绑定培训的版本。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Entity
@Table(name = "evaluation_dataset")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationDataset {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属企业 ID，NULL=平台公共模板 */
    @Column(name = "company_id")
    private UUID companyId;

    /** 题集名 */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** 描述 */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 用途：regression/acceptance/training */
    @Column(name = "purpose", nullable = false, length = 20)
    private String purpose;

    /** 状态：draft/in_review/approved/published/retired */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 当前版本号 */
    @Column(name = "current_version", nullable = false)
    @Builder.Default
    private Integer currentVersion = 1;

    /** 最新发布版题数（冗余加速列表） */
    @Column(name = "item_count", nullable = false)
    @Builder.Default
    private Integer itemCount = 0;

    /** 最近一次审核说明（退回时必填，提交人可见） */
    @Column(name = "last_review_reason", columnDefinition = "text")
    private String lastReviewReason;

    /** 最近一次审核人 */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    /** 最近一次审核时间 */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** 创建人 ID */
    @Column(name = "created_by")
    private UUID createdBy;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== 用途常量 ==========

    public static final String PURPOSE_REGRESSION = "regression";
    public static final String PURPOSE_ACCEPTANCE = "acceptance";
    public static final String PURPOSE_TRAINING = "training";

    // ========== 状态常量 ==========

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_IN_REVIEW = "in_review";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_RETIRED = "retired";

    private static final java.util.Set<String> PURPOSES = java.util.Set.of(
            PURPOSE_REGRESSION, PURPOSE_ACCEPTANCE, PURPOSE_TRAINING);

    /** 判断用途是否合法。 */
    public static boolean isValidPurpose(String purpose) {
        return purpose != null && PURPOSES.contains(purpose);
    }

    /** 更新前自动设置更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
