package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AI分身评估实体类
 *
 * <p>对应 skill_evaluation 表，记录对AI分身对话质量的四维评估结果，
 * 包括风格（30%）、一致性（30%）、行为表现（20%）和话术复用（20%）四个维度的评分及改进建议。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "skill_evaluation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillEvaluation {

    /**
     * 评估记录唯一标识
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * 所属AI分身ID
     */
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    /**
     * 关联对话会话ID
     */
    @Column(name = "conversation_id")
    private UUID conversationId;

    /**
     * 评估模式：qa=问答评估 / practice=对练评估 / auto_evaluate=自动评估 / acceptance_report=验收报告
     */
    @Column(length = 20)
    private String mode;

    /**
     * 评估人ID
     */
    @Column(name = "evaluator_id")
    private UUID evaluatorId;

    /**
     * 综合评分（0-100）
     */
    @Column(columnDefinition = "INT CHECK (score >= 0 AND score <= 100)")
    private Integer score;

    /**
     * 风格得分（权重30%）
     */
    @Column(name = "style_score")
    private Integer styleScore;

    /**
     * 一致性得分（权重30%）
     */
    @Column(name = "consistency_score")
    private Integer consistencyScore;

    /**
     * 行为表现得分（权重20%）
     */
    @Column(name = "behavior_score")
    private Integer behaviorScore;

    /**
     * 话术复用得分（权重20%）
     */
    @Column(name = "script_reuse_score")
    private Integer scriptReuseScore;

    /**
     * 评分详情（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "score_detail", columnDefinition = "JSONB")
    private String scoreDetail;

    /**
     * 优点列表（JSON数组，默认[]）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    @Builder.Default
    private String strengths = "[]";

    /**
     * 改进点列表（JSON数组，默认[]）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    @Builder.Default
    private String improvements = "[]";

    /**
     * 销冠示范话术
     */
    @Column(name = "demo_script", columnDefinition = "TEXT")
    private String demoScript;

    /**
     * 编辑后的回复
     */
    @Column(name = "edited_response", columnDefinition = "TEXT")
    private String editedResponse;

    /**
     * 编辑人ID
     */
    @Column(name = "edited_by")
    private UUID editedBy;

    /**
     * 创建时间（默认为当前时间）
     */
    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
