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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 站内信 —— 培训事件（待复核/认证/撤销/补训/延期/导出）的收件箱条目。
 *
 * <p>{@code dedupeKey} 用于同一事件的重复投递防抖（部分唯一索引），
 * 为空表示不做去重。通知写入失败不能影响业务事务，由
 * {@code TrainingNotificationService} 在事务提交后尽力投递。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Entity
@Table(name = "user_notification")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserNotification {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** C 端用户没有企业归属，允许为空 */
    @Column(name = "company_id")
    private UUID companyId;

    /** 收件人 */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 事件类型：training_pending_review / training_certified / ... */
    @Column(name = "type", nullable = false, length = 40)
    private String type;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** 前端跳转路径 */
    @Column(name = "link", length = 500)
    private String link;

    @Column(name = "biz_type", length = 40)
    private String bizType;

    @Column(name = "biz_id")
    private UUID bizId;

    /** 去重键（可选）；重复投递由部分唯一索引拦截 */
    @Column(name = "dedupe_key", length = 200)
    private String dedupeKey;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // ========== 事件类型常量 ==========

    public static final String TYPE_PENDING_REVIEW = "training_pending_review";
    public static final String TYPE_REVIEW_RESULT = "training_review_result";
    public static final String TYPE_CERTIFIED = "training_certified";
    public static final String TYPE_CERTIFICATION_REVOKED = "training_certification_revoked";
    public static final String TYPE_REMEDIATION = "training_remediation";
    public static final String TYPE_DEADLINE_EXTENDED = "training_deadline_extended";
    public static final String TYPE_EXPORT_READY = "training_export_ready";
    public static final String TYPE_EXPORT_FAILED = "training_export_failed";
    public static final String TYPE_STALE_REVIEW = "training_stale_review";
    /** 学员必修全部通过、等待主管确认认证 */
    public static final String TYPE_PENDING_CERTIFICATION = "training_pending_certification";
    /** 每日微练提醒 */
    public static final String TYPE_DAILY_DRILL = "training_daily_drill";
    public static final String TYPE_SALES_TASK_DUE_SOON = "sales_task_due_soon";
    public static final String TYPE_SALES_TASK_OVERDUE = "sales_task_overdue";
    public static final String TYPE_SALES_TASK_TRANSFERRED = "sales_task_transferred";
    public static final String TYPE_SALES_TASK_ESCALATED = "sales_task_escalated";

    /** 门户「预约演示」留资 */
    public static final String TYPE_DEMO_REQUEST = "demo_request";

    public boolean isRead() {
        return readAt != null;
    }
}
