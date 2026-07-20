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
 * 知识缺口实体 — 用户提问后 RAG 检索无匹配颗粒时记录。
 *
 * <p>每条记录代表一次"分身回答不了的问题"。管理员可通过此表发现
 * 知识盲区，针对性地补充颗粒或素材。Phase 1 开始采集数据，
 * Phase 2 在 Admin 仪表盘展示缺口列表。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-17
 */
@Entity
@Table(name = "knowledge_gap")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeGap {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属分身 ID */
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    /** 所属空间 ID */
    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    /** 用户提问原文（保留最后一次提问的内容） */
    @Column(name = "query", nullable = false, columnDefinition = "TEXT")
    private String query;

    /** 系统推测的场景标签（通过关键词匹配或 LLM 分类） */
    @Column(name = "scene_tag", length = 100)
    private String sceneTag;

    /** 该场景历史累计出现次数（写入时从已有记录 COUNT + 1） */
    @Column(name = "attempted_query_count", nullable = false)
    @Builder.Default
    private Integer attemptedQueryCount = 1;

    /**
     * 缺口状态。
     * <ul>
     *   <li>open — 待处理</li>
     *   <li>reviewing — 审查中</li>
     *   <li>resolved — 已补充颗粒/素材</li>
     *   <li>ignored — 不适合回答（如非业务问题）</li>
     * </ul>
     */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "open";

    /** 处理人（管理员用户名或 ID） */
    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    /** 处理时间 */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** 管理员备注（为什么忽略 / 怎么解决的） */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间（每次 count 增加时更新） */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 缺口文本的向量表示（1024维，DashScope text-embedding-v4）。
     * 仅由 SQL 层管理，JPA 不负责写入/更新。
     */
    @Column(name = "embedding", columnDefinition = "VECTOR(1024)", insertable = false, updatable = false)
    private String embedding;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
