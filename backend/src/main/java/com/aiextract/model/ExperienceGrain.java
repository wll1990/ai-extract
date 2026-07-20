package com.aiextract.model;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
 * 经验颗粒实体类
 *
 * <p>对应 experience_grain 表，存储从报告中拆解的独立经验单元，
 * 包含场景标签、判断信号、专家思维、标准话术和向量嵌入。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "experience_grain")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExperienceGrain {

    /**
     * 颗粒唯一标识
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 所属空间ID
     */
    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    /**
     * 所属报告ID
     */
    @Column(name = "report_id")
    private UUID reportId;

    /**
     * 来源素材ID（素材直传管道产生的颗粒关联）
     */
    @Column(name = "source_material_id")
    private UUID sourceMaterialId;

    /**
     * 颗粒来源类型：file_upload | interview
     */
    @Column(name = "source_type", length = 20)
    private String sourceType;

    /**
     * 关联的访谈会话ID（source_type=interview 时有值）
     */
    @Column(name = "source_interview_id")
    private UUID sourceInterviewId;

    /**
     * 场景标签
     */
    @Column(name = "scene_tag", length = 50)
    private String sceneTag;

    /**
     * 场景描述
     */
    @Column(name = "scene_description", columnDefinition = "TEXT")
    private String sceneDescription;

    /**
     * 专家思维
     */
    @Column(name = "expert_thought", columnDefinition = "TEXT")
    private String expertThought;

    /**
     * 标准话术
     */
    @Column(name = "standard_script", columnDefinition = "TEXT")
    private String standardScript;

    /**
     * 常见错误
     */
    @Column(name = "common_mistakes", columnDefinition = "TEXT")
    private String commonMistakes;

    /**
     * 适用条件
     */
    @Column(name = "applicable_condition", columnDefinition = "TEXT")
    private String applicableCondition;

    /**
     * 向量嵌入（1024维，text-embedding-v3）
     */
    /** JPA 不管理此列（pgvector 类型 JDBC 无法正确处理），通过原生 SQL 更新 */
    @Column(name = "embedding", columnDefinition = "VECTOR(1024)", insertable = false, updatable = false)
    private String embedding;

    /**
     * 颗粒权重（0.1-2.0，默认1.0）
     */
    @Column(name = "weight")
    private Double weight;

    /**
     * 颗粒状态: active / deprecated
     */
    @Column(name = "status", length = 20)
    private String status;

    /**
     * 管理员编辑后的内容
     */
    @Column(name = "edited_content", columnDefinition = "TEXT")
    private String editedContent;

    /**
     * 有帮助次数
     */
    @Column(name = "helpful_count", nullable = false)
    private Integer helpfulCount;

    /**
     * 无帮助次数
     */
    @Column(name = "unhelpful_count", nullable = false)
    private Integer unhelpfulCount;

    /**
     * 质量评分（0-5，对抗验证综合分）
     */
    @Column(name = "quality_score")
    private Double qualityScore;

    /**
     * 技能复制难度: beginner / intermediate / advanced / master
     */
    @Column(name = "difficulty_level", length = 20)
    private String difficultyLevel;

    /**
     * 对抗验证结果 JSON: {"specificity":4,"reproducibility":3,...}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "verification_notes", columnDefinition = "JSONB")
    private String verificationNotes;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
