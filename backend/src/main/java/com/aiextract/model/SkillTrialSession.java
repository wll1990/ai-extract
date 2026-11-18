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
 * 试问会话 —— 属主/管理员在发布前后演练分身用，与真实员工对话完全隔离。
 *
 * <p>试问消息只落 {@link SkillTrialMessage}，不写 skill_conversation / skill_message /
 * conversation_stats / grain_retrieve_log，因此不会影响任何看板与颗粒引用统计。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-18
 */
@Entity
@Table(name = "skill_trial_session")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillTrialSession {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** 首轮问题摘要，列表显示用；不单独维护 title */
    @Column(name = "first_question", length = 200)
    private String firstQuestion;

    @Column(name = "message_count", nullable = false)
    private Integer messageCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
