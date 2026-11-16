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
 * 一次销售复盘会话：发起人把客户对话文本贴进来，选一个教练分身生成报告。
 */
@Entity
@Table(name = "review_session")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewSession {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "reviewer_id", nullable = false)
    private UUID reviewerId;

    @Column(name = "coach_skill_id", nullable = false)
    private UUID coachSkillId;

    @Column(name = "coach_owner_id", nullable = false)
    private UUID coachOwnerId;

    @Column(name = "coach_name", length = 100)
    private String coachName;

    @Column(name = "coach_title", length = 200)
    private String coachTitle;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "topic", length = 200)
    private String topic;

    @Column(name = "transcript", nullable = false, columnDefinition = "TEXT")
    private String transcript;

    @Column(name = "transcript_chars", nullable = false)
    private int transcriptChars;

    @Column(name = "source", nullable = false, length = 20)
    private String source;

    @Column(name = "share_transcript_with_coach", nullable = false)
    private boolean shareTranscriptWithCoach;

    /** active / report_ready / converted / closed */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
