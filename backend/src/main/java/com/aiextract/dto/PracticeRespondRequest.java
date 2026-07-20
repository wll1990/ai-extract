package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 对练回应请求DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticeRespondRequest {

    /**
     * 对练会话ID（可选，用于追踪）
     */
    private String practiceId;

    /**
     * 对话记录ID（用于历史持久化）
     */
    private String conversationId;

    /**
     * 员工回应的内容（必填）
     */
    @NotBlank(message = "回应内容不能为空")
    private String message;

    /**
     * 场景上下文（对练场景设定描述）
     */
    private String sceneContext;

    /**
     * 对话历史（多轮对话的文本记录）
     */
    private String history;

    /**
     * 当前练习轮次（1-based）
     */
    private int roundNumber;

    /**
     * 练习角度总数
     */
    private int totalAngles;

    /**
     * 练习角度列表（该场景的核心技法角度，用于指导客户提什么问题）
     */
    private String practiceAngles;

    /**
     * 场景标签（用于加载对练颗粒做溯源展示，可选）
     */
    private String sceneTag;
}
