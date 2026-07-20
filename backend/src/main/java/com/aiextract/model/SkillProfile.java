package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
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
 * AI分身画像实体类
 *
 * <p>对应 skill_profile 表，定义AI分身的人设画像，
 * 包括性格、说话风格、背景经历、擅长领域和沟通偏好等核心人格特征。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "skill_profile")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillProfile {

    /**
     * 画像唯一标识
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * 所属AI分身ID（一对一，每个分身唯一）
     */
    @Column(name = "skill_id", nullable = false, unique = true)
    private UUID skillId;

    /**
     * 性格描述
     */
    @Column(columnDefinition = "TEXT")
    private String personality;

    /**
     * 说话风格
     */
    @Column(name = "speaking_style", columnDefinition = "TEXT")
    private String speakingStyle;

    /**
     * 背景经历
     */
    @Column(columnDefinition = "TEXT")
    private String background;

    /**
     * 口头禅
     */
    @Column(name = "common_phrases", columnDefinition = "TEXT")
    private String commonPhrases;

    /**
     * 擅长领域（JSON数组，默认[]）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "knowledge_domains", columnDefinition = "JSONB")
    @Builder.Default
    private String knowledgeDomains = "[]";

    /**
     * 沟通偏好（JSON数组，默认[]）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "communication_preferences", columnDefinition = "JSONB")
    @Builder.Default
    private String communicationPreferences = "[]";

    /**
     * 已知弱点备注
     */
    @Column(name = "weakness_notes", columnDefinition = "TEXT")
    private String weaknessNotes;

    /**
     * 额外上下文信息
     */
    @Column(name = "extra_context", columnDefinition = "TEXT")
    private String extraContext;

    /**
     * 创建时间（默认为当前时间）
     */
    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * 更新前自动设置更新时间
     */
    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
