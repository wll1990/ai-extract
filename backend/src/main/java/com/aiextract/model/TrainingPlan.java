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
 * 培训计划实体 —— 版本化的「新人培训方案」。
 *
 * <p>状态机：draft → published → retired。退役只阻止新分配，不终止已有学习。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "training_plan")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingPlan {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属企业 ID（企业隔离） */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** 计划名 */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** 描述 */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 状态：draft/published/retired */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_DRAFT;

    /** 当前版本号 */
    @Column(name = "current_version", nullable = false)
    @Builder.Default
    private Integer currentVersion = 1;

    /** 训练目标（AI 生成计划时写入：岗位/场景/达标线） */
    @Column(name = "goal", columnDefinition = "TEXT")
    private String goal;

    /** 是否由 AI 生成（true 时 UI 展示生成依据，仍走人工发布） */
    @Column(name = "generated_by_ai", nullable = false)
    @Builder.Default
    private Boolean generatedByAi = false;

    /** 生成说明：选了哪些信号、为什么推这些任务 */
    @Column(name = "generation_note", columnDefinition = "TEXT")
    private String generationNote;

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

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_RETIRED = "retired";

    private static final Set<String> STATUSES = Set.of(STATUS_DRAFT, STATUS_PUBLISHED, STATUS_RETIRED);

    /** 判断状态是否合法。 */
    public static boolean isValidStatus(String status) {
        return status != null && STATUSES.contains(status);
    }

    /** 草稿态才允许编辑任务。 */
    public boolean isDraft() {
        return STATUS_DRAFT.equals(status);
    }

    /** 已发布态才允许建立批次。 */
    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
