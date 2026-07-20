package com.aiextract.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AI 自动生成的候选技能颗粒 —— 从对话数据和反馈中发现的"新知识"。
 *
 * <p>管理员审核通过后，系统会将候选颗粒写入 {@link ExperienceGrain} 表
 * 并触发 DashScope 向量化，使其立即进入 RAG 检索范围。</p>
 *
 * <p>这是"平台自己发现规律"的核心输出物——不是告警文本，而是
 * 结构化的、可直接入库的销售经验颗粒。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-20
 */
@Entity
@Table(name = "candidate_grain")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateGrain {

    /** 审核状态常量 */
    public static final String STATUS_PENDING_REVIEW = "pending_review";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属分身（NULL = 跨分身通用颗粒） */
    @Column(name = "skill_id")
    private UUID skillId;

    /** 场景标签 */
    @Column(name = "scene_tag", nullable = false, length = 50)
    private String sceneTag;

    /** 场景描述 */
    @Column(name = "scene_description", columnDefinition = "TEXT")
    private String sceneDescription;

    /** AI 发现的专家思考/策略 */
    @Column(name = "expert_thought", nullable = false, columnDefinition = "TEXT")
    private String expertThought;

    /** AI 生成的标准话术 */
    @Column(name = "standard_script", columnDefinition = "TEXT")
    private String standardScript;

    /** AI 识别的常见错误话术 */
    @Column(name = "common_mistakes", columnDefinition = "TEXT")
    private String commonMistakes;

    /** 适用条件 */
    @Column(name = "applicable_condition", columnDefinition = "TEXT")
    private String applicableCondition;

    /** 产生此候选颗粒的洞察记录 */
    @Column(name = "source_insight_id", nullable = false)
    private UUID sourceInsightId;

    /** 数据依据（JSONB） */
    @Column(name = "source_evidence", nullable = false, columnDefinition = "JSONB")
    private String sourceEvidence;

    /** 审核状态 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 审核人 */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    /** 审核时间 */
    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /** 审核备注 */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = STATUS_PENDING_REVIEW;
        }
        if (this.sourceEvidence == null) {
            this.sourceEvidence = "{}";
        }
    }
}
