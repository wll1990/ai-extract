package com.aiextract.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 回答矫正记录 — admin 对 AI 回答的人工纠正。
 * 提交后关联颗粒的 weight 自动衰减 30%。
 *
 * @author AI Extract Team
 * @since 2026-07-30
 */
@Entity
@Table(name = "answer_correction")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnswerCorrection {

    /** 主键 */
    @Id
    private UUID id;

    /** 关联的分身 ID（权限校验 + 统计） */
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    /** 关联的会话 ID（可空 — admin 可能离线矫正） */
    @Column(name = "conversation_id")
    private UUID conversationId;

    /** 被矫正的 AI 消息 ID */
    @Column(name = "message_id")
    private UUID messageId;

    /** 用户当时问的问题 */
    @Column(name = "original_query", columnDefinition = "TEXT")
    private String originalQuery;

    /** AI 的错误回答 */
    @Column(name = "bad_response", columnDefinition = "TEXT")
    private String badResponse;

    /** Admin 给出的正确答案 */
    @Column(name = "corrected_response", columnDefinition = "TEXT")
    private String correctedResponse;

    /** 涉及的颗粒 ID 列表（JSONB: ["uuid1","uuid2"]）— 矫正后这些颗粒 weight × 0.7 */
    @Column(name = "grain_ids", columnDefinition = "JSONB")
    private String grainIds;

    /** 操作人标识 */
    @Column(name = "corrected_by", length = 100)
    private String correctedBy;

    /** 矫正时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
