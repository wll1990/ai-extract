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
 * AI分身对话会话实体类
 *
 * <p>对应 skill_conversation 表，记录用户与AI分身的每一次对话会话，
 * 支持问答、对练、快速提问和自由讨论四种对话模式。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "skill_conversation")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillConversation {

    /**
     * 会话唯一标识
     */
    @Id
    private UUID id;

    /**
     * 所属AI分身ID
     */
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    /**
     * 对话用户ID
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * 会话标题
     */
    @Column(length = 200)
    private String title;

    /**
     * 对话模式：qa=问答 / practice=对练 / quick=快速提问 / discuss=自由讨论 / talk=自由对话
     */
    @Column(length = 20)
    private String mode;

    /**
     * 创建时间
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
