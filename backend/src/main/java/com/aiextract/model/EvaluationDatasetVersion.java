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
import java.util.UUID;

/**
 * 题集版本实体 —— 发布即冻结，后续编辑复制新 draft 版本，不覆盖已发布。
 *
 * <p>UNIQUE(dataset_id, version)。已发布版本只读；改则从已发布版复制新 draft。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Entity
@Table(name = "evaluation_dataset_version")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationDatasetVersion {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属题集 ID */
    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    /** 版本号 */
    @Column(name = "version", nullable = false)
    private Integer version;

    /** 状态：draft/published/retired */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 冻结时快照题数 */
    @Column(name = "item_count", nullable = false)
    @Builder.Default
    private Integer itemCount = 0;

    /** 发布时间 */
    @Column(name = "frozen_at")
    private LocalDateTime frozenAt;

    /** 创建人 ID */
    @Column(name = "created_by")
    private UUID createdBy;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ========== 状态常量 ==========

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_RETIRED = "retired";
}
