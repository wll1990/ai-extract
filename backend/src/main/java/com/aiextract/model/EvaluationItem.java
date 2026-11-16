package com.aiextract.model;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
 * 评测题目实体 —— 题集内的单题。
 *
 * <p>JSONB 字段（String 存 JSON）：expected_points 预期要点（验收/考试金标）、
 * forbidden_points 禁止项（红线）、citation_requirement 引用要求、scoring_rule 评分规则快照、
 * retrieval_expected 检索期望（研发回归用途，期望命中的场景/知识/案例）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Entity
@Table(name = "evaluation_item")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationItem {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属企业 ID（冗余自 dataset，加速企业隔离查询） */
    @Column(name = "company_id")
    private UUID companyId;

    /** 所属题集 ID */
    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    /** 所属版本号 */
    @Column(name = "version", nullable = false)
    private Integer version;

    /** 题干/输入 */
    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    /** 固定上下文（可比较性） */
    @Column(name = "context", columnDefinition = "TEXT")
    private String context;

    /** 预期要点（金标，验收/考试用） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected_points", columnDefinition = "JSONB")
    private String expectedPoints;

    /** 禁止项（红线） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "forbidden_points", columnDefinition = "JSONB")
    private String forbiddenPoints;

    /** 引用要求 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "citation_requirement", columnDefinition = "JSONB")
    private String citationRequirement;

    /** 适用场景 */
    @Column(name = "scene_tag", length = 50)
    private String sceneTag;

    /** 金标依据/出处 */
    @Column(name = "gold_basis", columnDefinition = "TEXT")
    private String goldBasis;

    /** 评分规则（rubric 版本快照） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scoring_rule", columnDefinition = "JSONB")
    private String scoringRule;

    /** 检索期望（期望命中的场景/知识/案例，研发回归用途） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieval_expected", columnDefinition = "JSONB")
    private String retrievalExpected;

    /**
     * 事实标识：素材句子归一化后的 SHA-256，AI 出题去重主键（手工题为 NULL）。
     *
     * <p>标识落在「素材句子」上而不是题干上：同一素材重复出题时，题干措辞会变，
     * 但它引用的事实句子不会变（模型逐字摘抄，服务端吸附到所在句）。</p>
     */
    @Column(name = "fact_key", length = 64)
    private String factKey;

    /** 事实原文：被吸附的素材句子（截断 200 字），用于 prompt 避让与人工核对 */
    @Column(name = "fact_quote", length = 200)
    private String factQuote;

    /** 题序 */
    @Column(name = "order_no", nullable = false)
    @Builder.Default
    private Integer orderNo = 0;

    /** 状态：active/deprecated */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 是否由 AI 生成（出题任务产出），便于人工题与候选题区分 */
    @Column(name = "generated", nullable = false)
    @Builder.Default
    private Boolean generated = false;

    /** AI 生成依据（缺口/错题/知识引用与摘录） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", columnDefinition = "JSONB")
    private String evidence;

    /** 生成任务 ID（content_generation_job） */
    @Column(name = "generation_job_id")
    private UUID generationJobId;

    /** 人工审核人 / 时间 / 结论说明（AI 草稿审核台写入） */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    /** AI 草稿审核结论：approved / rejected / null（未审核） */
    @Column(name = "review_outcome", length = 20)
    private String reviewOutcome;

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

    /** AI 生成草稿：未审核，不参与运行抽题、考试与发布计数 */
    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DEPRECATED = "deprecated";

    /** 更新前自动设置更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
