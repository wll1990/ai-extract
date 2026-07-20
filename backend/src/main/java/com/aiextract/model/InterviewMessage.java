package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 访谈消息实体类
 *
 * <p>对应 interview_message 表，存储访谈过程中的每一条AI和用户消息，
 * 包括消息内容、角色、所属阶段和追问深度。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "interview_message")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewMessage {

    /**
     * 消息唯一标识
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 所属会话ID
     */
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /**
     * 消息角色：ai / user / system
     */
    @Column(name = "role", nullable = false, length = 10)
    private String role;

    /**
     * 消息内容
     */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 消息所属阶段：opening / storytelling / modeling / closing
     */
    @Column(name = "phase", length = 20)
    private String phase;

    /**
     * 追问深度（-1表示系统引导消息）
     */
    @Column(name = "depth", nullable = false)
    private Integer depth;

    /**
     * 阶段采集状态快照（JSON）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stage_status", columnDefinition = "JSONB")
    private String stageStatus;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
