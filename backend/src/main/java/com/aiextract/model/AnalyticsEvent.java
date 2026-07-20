package com.aiextract.model;

import jakarta.persistence.*;
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
 * 前端埋点事件 — 用户行为追踪。
 *
 * <p>前端通过 POST /api/v1/analytics/event 上报事件，
 * 后端直接写入此表。埋点失败不影响主流程。</p>
 *
 * <p>支持的事件类型：
 * <ul>
 *   <li>recommendation_show — 推荐问题曝光</li>
 *   <li>recommendation_click — 推荐问题点击</li>
 *   <li>mode_switch — 对话模式切换</li>
 *   <li>conversation_end — 对话结束</li>
 * </ul>
 * </p>
 *
 * @author AI Extract Team
 * @since 2026-07-17
 */
@Entity
@Table(name = "analytics_event")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsEvent {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 关联分身 ID（可为空） */
    @Column(name = "skill_id")
    private UUID skillId;

    /** 关联对话 ID（可为空） */
    @Column(name = "conversation_id")
    private UUID conversationId;

    /** 用户 ID（可为空） */
    @Column(name = "user_id")
    private UUID userId;

    /** 事件类型：recommendation_show / recommendation_click / mode_switch / conversation_end */
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /**
     * 事件数据（JSONB 格式）。
     * recommendation_click: {"question": "xxx", "skill_id": "xxx"}
     * mode_switch: {"from_mode": "qa", "to_mode": "talk"}
     * conversation_end: {"duration_seconds": 120, "total_rounds": 8}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_data", columnDefinition = "JSONB")
    private String eventData;

    /** 事件时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
