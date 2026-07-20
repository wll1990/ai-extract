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
 * AI 自动发现的洞察记录 —— 从对话数据/反馈/缺口/检索日志中识别规律和异常。
 *
 * <p>每条洞察是一条"AI 发现的可解释事实"：满意率骤降、缺口爆发、
 * 新高频场景等。严重洞察会产生候选颗粒（candidate_grain），
 * 管理员审核通过后正式入库。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-20
 */
@Entity
@Table(name = "auto_insight")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutoInsight {

    /** 洞察类型常量 */
    public static final String TYPE_GAP_BURST = "gap_burst";
    public static final String TYPE_SATISFACTION_DROP = "satisfaction_drop";
    public static final String TYPE_HIT_RATE_DROP = "hit_rate_drop";
    public static final String TYPE_NEW_PATTERN = "new_pattern";
    public static final String TYPE_INACTIVE = "inactive";

    /** 严重程度常量 */
    public static final String SEVERITY_CRITICAL = "critical";
    public static final String SEVERITY_WARNING = "warning";
    public static final String SEVERITY_INFO = "info";

    /** 状态常量 */
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_RESOLVED = "resolved";
    public static final String STATUS_IGNORED = "ignored";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属分身（NULL = 跨分身聚合洞察） */
    @Column(name = "skill_id")
    private UUID skillId;

    /** 洞察类型 */
    @Column(name = "type", nullable = false, length = 50)
    private String type;

    /** 洞察标题 */
    @Column(name = "title", nullable = false, length = 500)
    private String title;

    /** 洞察描述 */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 严重程度 */
    @Column(name = "severity", nullable = false, length = 20)
    private String severity;

    /** 数据依据（JSONB） */
    @Column(name = "evidence", nullable = false, columnDefinition = "JSONB")
    private String evidence;

    /** 关联的候选颗粒 ID */
    @Column(name = "candidate_grain_id")
    private UUID candidateGrainId;

    /** 状态 */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 处理人 */
    @Column(name = "resolved_by")
    private UUID resolvedBy;

    /** 处理时间 */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = STATUS_ACTIVE;
        }
        if (this.severity == null) {
            this.severity = SEVERITY_INFO;
        }
        if (this.evidence == null) {
            this.evidence = "{}";
        }
    }
}
