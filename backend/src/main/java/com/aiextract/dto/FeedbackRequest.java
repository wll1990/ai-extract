package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 反馈请求DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackRequest {

    /** 会话标识 */
    private String sessionId;

    /**
     * 经验颗粒ID（允许 null：RAG 无匹配时 AI 未引用颗粒，用户仍可打分）
     */
    private String grainId;

    /** 是否有帮助 */
    private Boolean helpful;

    /** 所属对话 ID（用于关联完整上下文） */
    private String conversationId;

    /** 用户当时的提问原文（用于管理员审查时直接查看） */
    private String query;

    /** AI 回答截取前 500 字（同上） */
    private String aiResponse;

    /** 回答时的 RAG 平均匹配度（0~1） */
    private Double ragScore;
}
