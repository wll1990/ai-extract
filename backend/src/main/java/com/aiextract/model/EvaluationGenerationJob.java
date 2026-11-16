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
 * AI 生成候选任务实体 —— 从知识/案例自动生成题目候选（异步轮询）。
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Entity
@Table(name = "evaluation_generation_job")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationGenerationJob {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属题集 ID */
    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    /** 来源类型：knowledge/case */
    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    /** 状态：running/done/failed */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 产出候选数 */
    @Column(name = "produced_count", nullable = false)
    @Builder.Default
    private Integer producedCount = 0;

    /** 因重复/事实无法定位被丢弃的数量（用于解释 0 产出） */
    @Column(name = "skipped_count", nullable = false)
    @Builder.Default
    private Integer skippedCount = 0;

    /** 模型返回但缺少事实依据被拒绝的数量 */
    @Column(name = "rejected_count", nullable = false)
    @Builder.Default
    private Integer rejectedCount = 0;

    /** 失败原因 */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 创建人 ID */
    @Column(name = "created_by")
    private UUID createdBy;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== 来源类型常量 ==========

    public static final String SOURCE_TYPE_KNOWLEDGE = "knowledge";
    public static final String SOURCE_TYPE_CASE = "case";

    // ========== 状态常量 ==========

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    /** 更新前自动设置更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
