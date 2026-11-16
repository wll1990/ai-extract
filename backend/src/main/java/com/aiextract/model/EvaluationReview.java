package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 评测人工复核实体 —— 追加式记录，一次人工动作一行。
 *
 * <p>复核不覆盖 AI 原分：{@code originalScore} 保存 AI 评分快照，
 * {@code adjustedScore} 保存人工改分，两者同时可查。
 * {@code reason} 必填，保证每条改分可追溯。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "evaluation_review")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationReview {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 单题执行 ID */
    @Column(name = "run_item_id", nullable = false)
    private UUID runItemId;

    /** 复核人 ID */
    @Column(name = "reviewer_id", nullable = false)
    private UUID reviewerId;

    /** 动作：adjust（改分）/ undetermined（无法判断）/ confirm（确认 AI 结论） */
    @Column(name = "action", nullable = false, length = 20)
    private String action;

    /** 理由（必填） */
    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    /** AI 原分快照（不可丢） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "original_score", columnDefinition = "JSONB")
    private String originalScore;

    /** 人工改分 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "adjusted_score", columnDefinition = "JSONB")
    private String adjustedScore;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ========== 动作常量 ==========

    public static final String ACTION_ADJUST = "adjust";
    public static final String ACTION_UNDETERMINED = "undetermined";
    public static final String ACTION_CONFIRM = "confirm";

    /** 判断复核动作是否合法。 */
    public static boolean isValidAction(String action) {
        return ACTION_ADJUST.equals(action)
                || ACTION_UNDETERMINED.equals(action)
                || ACTION_CONFIRM.equals(action);
    }
}
