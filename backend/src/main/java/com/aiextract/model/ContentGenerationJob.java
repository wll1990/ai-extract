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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AI 内容生成任务 — 知识/案例/考题草稿的统一留痕。
 *
 * <p>记录来源、产出数、错误与模型；草稿通过 generation_job_id 反查到这里，
 * 用于采纳率统计与「为什么生成这条」的审计。</p>
 */
@Entity
@Table(name = "content_generation_job")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentGenerationJob {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** knowledge / case / eval_item / derive */
    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    /** knowledge_gap / training_failure / eval_issue / conversation / manual */
    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    @Column(name = "source_ref", length = 128)
    private String sourceRef;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "produced_count", nullable = false)
    private Integer producedCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "JSONB")
    private String detail;

    /** 任务产出引用：生成类为 {"ids":[...]}，派生类为派生对象 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "JSONB")
    private String result;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "model", length = 100)
    private String model;

    @Column(name = "dedupe_key", length = 200)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    public static final String KIND_KNOWLEDGE = "knowledge";
    public static final String KIND_CASE = "case";
    public static final String KIND_EVAL_ITEM = "eval_item";
    public static final String KIND_DERIVE = "derive";
    public static final String KIND_PROMOTE_ISSUES = "promote_issues";
}
