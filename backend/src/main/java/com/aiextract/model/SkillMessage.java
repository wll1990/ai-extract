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
 * AI分身对话消息实体类
 *
 * <p>对应 skill_message 表，存储AI分身对话中的每一条消息，
 * 支持用户、AI助手和系统三种角色，可关联经验颗粒和报告。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "skill_message")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillMessage {

    /**
     * 消息唯一标识
     */
    @Id
    private UUID id;

    /**
     * 所属对话会话ID
     */
    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    /**
     * 消息角色：user=用户 / assistant=AI分身 / system=系统
     */
    @Column(length = 20, nullable = false)
    private String role;

    /**
     * 角色展示名：我 / 销冠 / 客户 / 我（销冠）
     */
    @Column(name = "role_label", length = 20)
    private String roleLabel;

    /**
     * 消息内容
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * 关联的经验颗粒ID
     */
    @Column(name = "grain_id")
    private UUID grainId;

    /**
     * 关联的报告ID
     */
    @Column(name = "report_id")
    private UUID reportId;

    /**
     * 创建时间
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
