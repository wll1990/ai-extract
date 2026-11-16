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
 * 颗粒纠错建议 — 员工在分身上看到错误颗粒时提交，管理员审核后落库。
 *
 * <p>不允许员工直接改颗粒：避免未审核的「经验」进入检索。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
@Entity
@Table(name = "grain_correction_suggestion")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GrainCorrectionSuggestion {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "grain_id", nullable = false)
    private UUID grainId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    @Column(name = "suggested_by", nullable = false)
    private UUID suggestedBy;

    /** 为什么认为这条颗粒有问题（必填） */
    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    /** 建议修改后的字段，为空表示不修改 */
    @Column(name = "scene_description", columnDefinition = "TEXT")
    private String sceneDescription;

    @Column(name = "expert_thought", columnDefinition = "TEXT")
    private String expertThought;

    @Column(name = "standard_script", columnDefinition = "TEXT")
    private String standardScript;

    @Column(name = "common_mistakes", columnDefinition = "TEXT")
    private String commonMistakes;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_PENDING;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", columnDefinition = "TEXT")
    private String reviewNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
