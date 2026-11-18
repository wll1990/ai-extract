package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * 模型技能（Agent Skill）—— 给模型按需加载的方法论/话术。
 *
 * <p>渐进披露：目录只带 key/name/description，正文由 {@code use_skill} 按需读取。
 * 平台级技能 companyId 为空；企业级技能由企业维护。</p>
 *
 * @since 2026-11-18
 */
@Entity
@Table(name = "agent_skill")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSkill {

    public static final String SOURCE_BUILTIN = "builtin";
    public static final String SOURCE_PLATFORM = "platform";
    public static final String SOURCE_TENANT = "tenant";
    public static final String SOURCE_MCP = "mcp";

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_ARCHIVED = "archived";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "skill_key", nullable = false, length = 64, updatable = false)
    private String skillKey;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", nullable = false, length = 1024)
    private String description;

    @Column(name = "body_md", nullable = false, columnDefinition = "TEXT")
    private String bodyMd;

    @Column(name = "source", nullable = false, length = 16, updatable = false)
    private String source;

    @Column(name = "company_id", updatable = false)
    private UUID companyId;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "token_estimate", nullable = false)
    private Integer tokenEstimate;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Integer lockVersion;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "archived_by")
    private UUID archivedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "archived_at")
    private Instant archivedAt;

    public boolean isPlatformLevel() {
        return companyId == null;
    }

    public boolean isPublished() {
        return STATUS_PUBLISHED.equals(status);
    }

    public boolean isDraft() {
        return STATUS_DRAFT.equals(status);
    }
}
