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
 * 对话统计 — 每次 AI 回复生成一条记录。
 *
 * <p>是飞轮所有报表的单一数据源。无论对话正常结束、超时还是报错，
 * 都会在 doFinally 回调中写入。Admin 测试对话通过 is_test 标记过滤。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-17
 */
@Entity
@Table(name = "conversation_stats")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationStats {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属分身 ID */
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    /** 对话 ID（多轮对话中多条记录共享同一个 conversation_id） */
    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    /** 用户 ID */
    @Column(name = "user_id")
    private UUID userId;

    /** 对话模式：qa / discuss / talk / practice / enterprise */
    @Column(name = "mode", nullable = false, length = 20)
    private String mode;

    /** 本轮高匹配颗粒数（similarity ≥ 配置阈值的颗粒数） */
    @Column(name = "rag_high_count", nullable = false)
    @Builder.Default
    private Integer ragHighCount = 0;

    /** 本轮参考匹配颗粒数（similarity 在 ref 阈值与 high 阈值之间） */
    @Column(name = "rag_ref_count", nullable = false)
    @Builder.Default
    private Integer ragRefCount = 0;

    /** 本轮无匹配次数（RAG 返回空结果时记为 1） */
    @Column(name = "rag_none_count", nullable = false)
    @Builder.Default
    private Integer ragNoneCount = 0;

    /** 本轮 RAG 平均相似度（0~1） */
    @Column(name = "rag_avg_similarity")
    private Double ragAvgSimilarity;

    /** 本轮 👍 次数（对话结束后回填，初始为 0） */
    @Column(name = "feedback_up", nullable = false)
    @Builder.Default
    private Integer feedbackUp = 0;

    /** 本轮 👎 次数 */
    @Column(name = "feedback_down", nullable = false)
    @Builder.Default
    private Integer feedbackDown = 0;

    /**
     * 异常类型。
     * <ul>
     *   <li>NULL — 正常结束</li>
     *   <li>timeout — AI 响应超时</li>
     *   <li>error — AI 返回错误</li>
     *   <li>cancelled — 用户取消</li>
     * </ul>
     */
    @Column(name = "error_type", length = 20)
    private String errorType;

    /** Admin 测试对话标记（统计查询时需 WHERE is_test = FALSE 过滤） */
    @Column(name = "is_test", nullable = false)
    @Builder.Default
    private Boolean isTest = false;

    /** LLM 生成耗时（毫秒） */
    @Column(name = "llm_duration_ms")
    private Integer llmDurationMs;

    /** 端到端总耗时（毫秒），从请求进入到 SSE 流结束 */
    @Column(name = "total_duration_ms")
    private Integer totalDurationMs;

    /** 记录创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 管道类型：individual=个人分身, organization=组织分身, enterprise=企业调度。
     * 用于跨类型统计区分。注意 skill_id 字段在不同类型下的语义不同。
     * @since 2026-07-28 V31 迁移
     */
    @Column(name = "skill_type", length = 10)
    private String skillType;
}
