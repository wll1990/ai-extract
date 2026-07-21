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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AI分身Skill实体类
 *
 * <p>对应 skill 表，每个空间最多一个Skill。
 * 管理销冠AI分身的状态、模型配置和生成状态。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "skill")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {

    /**
     * Skill唯一标识
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 所属空间ID（每个空间一个Skill）
     */
    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    /**
     * 使用的模型名称
     */
    @Column(name = "model_name", length = 100)
    private String modelName;

    /**
     * 模型配置（JSON）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "model_config", columnDefinition = "JSONB")
    private String modelConfig;

    /**
     * Skill状态：generating → reviewing → published / discarded
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    // ---- 发布补充信息字段 ----

    /**
     * 对外展示名称
     */
    @Column(name = "display_name", length = 200)
    private String displayName;

    /**
     * 销冠真实姓名（展示用）
     */
    @Column(name = "owner_name", length = 100)
    private String ownerName;

    /**
     * 销冠职位
     */
    @Column(name = "owner_title", length = 200)
    private String ownerTitle;

    /**
     * 分身头像URL（V11 迁移已建列，此前未映射）
     */
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    /**
     * 所属部门
     */
    @Column(name = "department", length = 200)
    private String department;

    /**
     * 从业年限
     */
    @Column(name = "seniority", length = 50)
    private String seniority;

    /**
     * 标签（JSON数组，如["金融","B2B"]）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "JSONB DEFAULT '[]'")
    private String tags;

    /**
     * 适用场景（JSON数组，如["初次拜访","异议处理"]）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_scenarios", columnDefinition = "JSONB DEFAULT '[]'")
    private String targetScenarios;

    /**
     * 已知局限性
     */
    @Column(name = "limitations", columnDefinition = "TEXT")
    private String limitations;

    /**
     * 发布审核备注
     */
    @Column(name = "publish_notes", columnDefinition = "TEXT")
    private String publishNotes;

    /**
     * 发布时间
     */
    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /**
     * 发布人ID
     */
    @Column(name = "published_by")
    private UUID publishedBy;

    /**
     * 分身开场白 — 聊天页入场态展示，一般为专家自我介绍或欢迎语。
     * @since 2026-07-20 V18 迁移
     */
    @Column(name = "opening_message", columnDefinition = "TEXT")
    private String openingMessage;

    /**
     * 对练场景开场白缓存（JSON: {"场景标签": "客户开场白话术", ...}）。
     * 发布时 @Async 预生成，HTTP 路径直接读此字段，不调 AI。
     * @since 2026-07-22
     */
    @Column(name = "practice_openings", columnDefinition = "TEXT")
    private String practiceOpenings;

    /**
     * 领域标识，默认 sales。驱动预检、萃取、Chat 的领域知识库。
     */
    @Column(length = 20)
    private String domain;

    /**
     * Talk 模式开场配置（JSONB）
     * {"showRecommendedQuestions":false, "greetingMode":"auto", "showSceneTags":false}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "talk_config", columnDefinition = "JSONB DEFAULT '{}'")
    private String talkConfig;

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
