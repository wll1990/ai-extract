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
import java.util.UUID;

/**
 * 试问消息 —— 试问会话内的一问一答。
 *
 * <p>{@code meta} 保存本次回答引用的颗粒快照（grainIds / 场景标签 / 平均相似度 /
 * refineDraftId），用于右栏「引用了哪几条经验」与事后回看。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-18
 */
@Entity
@Table(name = "skill_trial_message")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillTrialMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /** {"grainIds":[],"grainTags":[],"avgSimilarity":"0.82","refineDraftId":"uuid"} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "meta", columnDefinition = "JSONB")
    private String meta;

    /** 前端幂等键，同一会话内重复提交不重复落库 */
    @Column(name = "client_message_id", length = 64)
    private String clientMessageId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
