package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 访谈会话实体类
 *
 * <p>对应 interview_session 表，管理AI萃取访谈的完整生命周期，
 * 包括四阶段推进、素材采集状态、邀请码和萃取师关联。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "interview_session")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewSession {

    /**
     * 会话唯一标识
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 所属空间ID
     */
    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    /**
     * 萃取主题
     */
    @Column(name = "topic", length = 200)
    private String topic;

    /**
     * 会话状态：created / in_progress / paused / completed / abandoned / failed
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /**
     * 当前阶段：opening / storytelling / modeling / closing
     */
    @Column(name = "current_phase", nullable = false, length = 20)
    private String currentPhase;

    /**
     * 是否已采集案例故事
     */
    @Column(name = "collect_case_story", nullable = false)
    private Boolean collectCaseStory;

    /**
     * 是否已采集核心步骤
     */
    @Column(name = "collect_steps", nullable = false)
    private Boolean collectSteps;

    /**
     * 是否已采集关键决策
     */
    @Column(name = "collect_decision", nullable = false)
    private Boolean collectDecision;

    /**
     * 是否已采集专家心法
     */
    @Column(name = "collect_mindset", nullable = false)
    private Boolean collectMindset;

    /**
     * 是否已采集适用边界
     */
    @Column(name = "collect_boundary", nullable = false)
    private Boolean collectBoundary;

    /**
     * 是否已采集检查清单
     */
    @Column(name = "collect_checklist", nullable = false)
    private Boolean collectChecklist;

    /**
     * 邀请码
     */
    @Column(name = "invite_code", length = 50)
    private String inviteCode;

    /**
     * 访谈类型：sales=销冠萃取  expert=萃取师访谈
     */
    @Column(name = "interview_type", length = 20)
    private String interviewType;

    /**
     * 领域ID，如 sales.b2b_enterprise / finance.secondary_market
     */
    @Column(name = "domain", length = 64)
    private String domain;

    /**
     * 关联的萃取师Skill ID
     */
    @Column(name = "expert_skill_id")
    private UUID expertSkillId;

    /**
     * 最后活跃时间
     */
    @Column(name = "last_active_at", nullable = false)
    private LocalDateTime lastActiveAt;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 结束时间
     */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /**
     * 更新前自动设置最后活跃时间
     */
    @PreUpdate
    public void preUpdate() {
        this.lastActiveAt = LocalDateTime.now();
    }
}
