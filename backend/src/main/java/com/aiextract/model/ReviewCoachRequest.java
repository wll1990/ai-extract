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
 * 请真人教练补充点评的请求。每个复盘最多一条。
 */
@Entity
@Table(name = "review_coach_request")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewCoachRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "session_id", nullable = false, unique = true)
    private UUID sessionId;

    @Column(name = "coach_user_id", nullable = false)
    private UUID coachUserId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "message", length = 500)
    private String message;

    /** pending / done / cancelled */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 教练首次查看时间（标记已读），null 表示未读 */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /** 教练回复时间（台账任务化：「已回复」状态） */
    @Column(name = "replied_at")
    private LocalDateTime repliedAt;

    /** 教练回复内容 */
    @Column(name = "reply_content", columnDefinition = "TEXT")
    private String replyContent;
}
