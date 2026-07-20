package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 萃取师经验颗粒实体类
 *
 * <p>对应 expert_grain 表，存储每一位萃取师的独立经验颗粒，
 * 包含7大类别、优先级、共识类型和向量嵌入。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "expert_grain")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpertGrain {

    /**
     * 颗粒唯一标识
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 所属萃取师ID
     */
    @Column(name = "expert_id", nullable = false)
    private UUID expertId;

    /**
     * 分类：judgment_intuition=判断直觉 / mental_model=心智模型 / failure_lesson=失败教训 / validation_method=验证方法 / metaphor_framework=隐喻框架 / rhythm_sense=节奏感知 / typing_method=类型化方法
     */
    @Column(name = "category", nullable = false, length = 50)
    private String category;

    /**
     * 来源类型：interview=访谈 / document=文档
     */
    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    /**
     * 领域ID，继承自 ExpertSkill.domain
     */
    @Column(name = "domain", length = 64)
    private String domain;

    /**
     * 场景描述
     */
    @Column(name = "scene_description", columnDefinition = "TEXT")
    private String sceneDescription;

    /**
     * 知识内容
     */
    @Column(name = "knowledge_content", nullable = false, columnDefinition = "TEXT")
    private String knowledgeContent;

    /**
     * 应用规则
     */
    @Column(name = "application_rule", columnDefinition = "TEXT")
    private String applicationRule;

    /**
     * 优先级（1-5，默认1）
     */
    @Column(name = "priority", nullable = false)
    private Integer priority;

    /**
     * 共识类型：single=单人 / consensus=共识 / conflict=冲突
     */
    @Column(name = "consensus_type", nullable = false, length = 20)
    private String consensusType;

    /**
     * 共识萃取师ID列表（JSON数组）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "consensus_expert_ids", columnDefinition = "JSONB")
    private String consensusExpertIds;

    /**
     * 颗粒状态：active=有效 / under_review=审核中 / deprecated=已废弃
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /**
     * 向量嵌入（1536维）
     */
    @Column(name = "embedding", columnDefinition = "VECTOR(1536)", insertable = false, updatable = false)
    private String embedding;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 更新前自动设置更新时间
     */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
