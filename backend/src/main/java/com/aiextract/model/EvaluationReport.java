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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 评测验收报告实体 —— 版本化，修订产生新版本，不覆盖历史。
 *
 * <p>冻结时写入 {@code summary}（总题/成功/通过/红线失败/未判定/耗时/用量）与
 * {@code content}（报告正文）；{@code status=finalized} 后不可再改，
 * 再次冻结只能新建 {@code version+1}。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "evaluation_report")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationReport {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属批次 ID */
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    /** 报告版本号：同批次内唯一（run_id, version） */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 1;

    /** 状态：draft/finalized */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_DRAFT;

    /** 汇总：总题数/执行成功/完成评分/通过/红线失败/未判定/耗时/用量 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", columnDefinition = "JSONB")
    private String summary;

    /** 报告正文（冻结时生成） */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** 定稿人 ID */
    @Column(name = "finalized_by")
    private UUID finalizedBy;

    /** 定稿时间 */
    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== 状态常量 ==========

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_FINALIZED = "finalized";

    /** 更新前自动设置更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
