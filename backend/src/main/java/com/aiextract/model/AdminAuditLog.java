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
 * 管理员操作审计 — 所有 Admin 写操作记录。
 *
 * <p>Phase 1 建表，Phase 3 开始写入。Admin 编辑颗粒、处理缺口、
 * 修改画像、编辑提示词等写操作均会写入此表，形成完整的操作追踪链。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-17
 */
@Entity
@Table(name = "admin_audit_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminAuditLog {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 操作人 ID */
    @Column(name = "admin_id", nullable = false)
    private UUID adminId;

    /**
     * 操作类型。
     * <ul>
     *   <li>edit_grain — 编辑颗粒</li>
     *   <li>deprecate_grain — 废弃颗粒</li>
     *   <li>create_grain — 新增颗粒</li>
     *   <li>resolve_gap — 处理缺口</li>
     *   <li>edit_domain — 编辑领域配置</li>
     *   <li>edit_prompt — 编辑提示词</li>
     * </ul>
     */
    @Column(name = "action", nullable = false, length = 50)
    private String action;

    /** 操作对象类型：grain / gap / prompt / domain */
    @Column(name = "target_type", nullable = false, length = 50)
    private String targetType;

    /** 操作对象 ID */
    @Column(name = "target_id")
    private UUID targetId;

    /**
     * 操作详情（JSONB）。
     * 编辑颗粒: {"field": "standard_script", "old_value": "...", "new_value": "..."}
     * 处理缺口: {"from_status": "open", "to_status": "resolved", "note": "已补充颗粒"}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "JSONB")
    private String detail;

    /** 操作时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
