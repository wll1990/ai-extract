package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 分身对话请求DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillChatRequest {

    /**
     * 用户提问内容（必填）
     */
    @NotBlank(message = "消息内容不能为空")
    private String message;

    /**
     * 会话标识（可选，用于连续对话）
     */
    private String sessionId;

    /**
     * 渠道标识：web / im（可选）
     */
    private String channel;

    /**
     * 对话模式：quick / discuss / practice（可选，不传则自动识别）
     */
    private String mode;

    /** 会话ID（持久化会话，优先使用） */
    private String conversationId;

    /**
     * 对话历史（多轮对话的文本记录，可选）
     */
    private String history;

    /** Admin 测试对话标记（统计时过滤，可选，默认 false） */
    private Boolean isTest;
}
