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
 * 组织/综合分身实体 — 聚合多个个人分身的部门级 AI 知识库。
 *
 * <p>与 {@link Skill} 独立：组织分身不属于任何个人 space，有独立的生命周期、
 * 统计字段和管理 CRUD。成员通过 memberSkillIds（JSONB）手动勾选，
 * 检索时跨成员 space 做多空间 pgvector 语义检索。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-28
 */
@Entity
@Table(name = "organization_skill")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationSkill {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属企业 ID（租户隔离） */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** 组织名称，如"华东销售部" */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** 描述/副标题 */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * 成员分身 ID 列表（JSONB 数组）。
     * MVP：管理员手动勾选。Phase 2 扩展 match_rule 动态匹配。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "member_skill_ids", columnDefinition = "JSONB DEFAULT '[]'")
    private String memberSkillIds;

    /** 组织头像 URL */
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    /** 开场白/欢迎语 */
    @Column(name = "opening_message", columnDefinition = "TEXT")
    private String openingMessage;

    /** 名片页 3 段式专业介绍 JSON，与 opening_message 互补 */
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "intro_profile", columnDefinition = "JSONB")
    private String introProfile;

    /** 状态：draft → published → archived */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 领域标识，默认 sales */
    @Column(length = 20)
    private String domain;

    // ═══════════════════════════════════════════════════════════
    // 互动统计（SkillStatsScheduler 每5分钟批量聚合写入）
    // ═══════════════════════════════════════════════════════════

    /** 近30天不重复对话次数 */
    @Column(name = "conversation_count")
    private Integer conversationCount;

    /** 近30天不重复用户数（跨成员去重：同用户问多成员只算一次） */
    @Column(name = "user_count")
    private Integer userCount;

    /** 满意度 0-100（跨成员加权平均） */
    @Column(name = "satisfaction_rate")
    private Integer satisfactionRate;

    /** 最近一次对话时间 */
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    /** 创建人 ID */
    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
