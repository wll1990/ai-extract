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
 * RAG 检索日志 — 每次语义检索命中记录。
 *
 * <p>每次 RAG 检索命中 5 条颗粒，每条颗粒写一行。
 * 用于分析检索质量（相似度分布）、场景覆盖度、改写效果对比。
 * 数据保留 30 天，定时任务自动清理。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-17
 */
@Entity
@Table(name = "grain_retrieve_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrainRetrieveLog {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属分身 ID */
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    /** 所属对话 ID */
    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    /** 用户原始提问（query_rewrite 改写前） */
    @Column(name = "original_query", columnDefinition = "TEXT")
    private String originalQuery;

    /** LLM 改写后的查询（如未改写则等于 original_query） */
    @Column(name = "rewritten_query", columnDefinition = "TEXT")
    private String rewrittenQuery;

    /** 命中的颗粒 ID */
    @Column(name = "grain_id", nullable = false)
    private UUID grainId;

    /** 颗粒的场景标签（冗余存储，方便按场景聚合查询） */
    @Column(name = "scene_tag", length = 100)
    private String sceneTag;

    /** 余弦相似度（0~1，pgvector <=> 算子计算） */
    @Column(name = "similarity", nullable = false)
    private Double similarity;

    /**
     * 分层标记。
     * <ul>
     *   <li>high — 高匹配（≥ 领域配置的 ragHighThreshold）</li>
     *   <li>ref — 参考（≥ 领域配置的 ragRefThreshold）</li>
     *   <li>NULL — 低匹配（低于 ref 阈值）</li>
     * </ul>
     */
    @Column(name = "tier", length = 10)
    private String tier;

    /** 排名（1-based，按 weighted score 降序） */
    @Column(name = "position", nullable = false)
    private Integer position;

    /** 检索时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
