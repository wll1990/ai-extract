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
 * 用户反馈记录 — 每次 👍/👎 打分完整留存。
 *
 * <p>与 experience_grain 的 helpful_count/unhelpful_count 不同的是，
 * 此表记录了每次打分的完整上下文（用户提问、AI 回答、RAG 分数），
 * 支持按时间/场景/用户维度分析回答质量。</p>
 *
 * <p>grain_id 允许 NULL：RAG 无匹配时 AI 回答不引用任何颗粒，用户仍可打分。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-17
 */
@Entity
@Table(name = "feedback_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackLog {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属分身 ID */
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    /** 所属对话 ID */
    @Column(name = "conversation_id")
    private UUID conversationId;

    /** 被评分的 AI 消息 ID */
    @Column(name = "message_id")
    private UUID messageId;

    /** 打分用户 ID */
    @Column(name = "user_id")
    private UUID userId;

    /** 关联的经验颗粒（NULL 表示无匹配颗粒时的打分） */
    @Column(name = "grain_id")
    private UUID grainId;

    /** 评分：up = 有帮助，down = 没帮助 */
    @Column(name = "rating", nullable = false, length = 10)
    private String rating;

    /** 用户当时的提问原文 */
    @Column(name = "query", columnDefinition = "TEXT")
    private String query;

    /** AI 回答截取前 500 字（管理员审查时无需翻原始对话） */
    @Column(name = "ai_response", length = 500)
    private String aiResponse;

    /** 回答时的 RAG 平均匹配度（0~1） */
    @Column(name = "rag_score")
    private Double ragScore;

    /**
     * 数据来源。
     * <ul>
     *   <li>user — 用户实时打分</li>
     *   <li>backfill — 存量数据迁移</li>
     * </ul>
     */
    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private String source = "user";

    /** 打分时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
