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
import java.util.Set;
import java.util.UUID;

/**
 * 培训复核记录实体 —— 主管/导师对一次尝试的结论。
 *
 * <p>只追加，不覆盖：原分存 {@code originalScore}，复核后分存 {@code adjustedScore}，
 * 历史记录永远可追溯。退回必须写具体要求（reason 非空，由 CHECK 兜底）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "training_review")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingReview {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 被复核的尝试 ID */
    @Column(name = "attempt_id", nullable = false)
    private UUID attemptId;

    /** 复核人 ID */
    @Column(name = "reviewer_id")
    private UUID reviewerId;

    /** 复核动作：pass/reject */
    @Column(name = "action", nullable = false, length = 20)
    private String action;

    /** 复核理由（必填，退回时写具体要求） */
    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    /** 原评分（保留） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "original_score", columnDefinition = "JSONB")
    private String originalScore;

    /** 复核后评分 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "adjusted_score", columnDefinition = "JSONB")
    private String adjustedScore;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ========== 动作常量 ==========

    public static final String ACTION_PASS = "pass";
    public static final String ACTION_REJECT = "reject";

    private static final Set<String> ACTIONS = Set.of(ACTION_PASS, ACTION_REJECT);

    /** 判断动作是否合法。 */
    public static boolean isValidAction(String action) {
        return action != null && ACTIONS.contains(action);
    }
}
