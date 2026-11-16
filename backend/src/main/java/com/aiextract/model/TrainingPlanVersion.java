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

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 培训计划版本实体 —— 发布即冻结任务集。
 *
 * <p>已发布版本的 {@link TrainingTask} 只读；编辑必须通过「新建版本」复制出 draft。
 * 批次绑定 `plan_id + version`，因此计划更新不会重置已分配学员的进度。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "training_plan_version")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingPlanVersion {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属计划 ID */
    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    /** 版本号 */
    @Column(name = "version", nullable = false)
    private Integer version;

    /** 状态：draft/published */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_DRAFT;

    /** 冻结时任务数快照 */
    @Column(name = "task_count", nullable = false)
    @Builder.Default
    private Integer taskCount = 0;

    /** 发布时间 */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /** 创建人 ID */
    @Column(name = "created_by")
    private UUID createdBy;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ========== 状态常量 ==========

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";

    private static final Set<String> STATUSES = Set.of(STATUS_DRAFT, STATUS_PUBLISHED);

    /** 判断状态是否合法。 */
    public static boolean isValidStatus(String status) {
        return status != null && STATUSES.contains(status);
    }

    /** 是否已冻结。 */
    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }
}
