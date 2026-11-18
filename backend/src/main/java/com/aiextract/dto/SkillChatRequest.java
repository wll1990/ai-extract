package com.aiextract.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /**
     * 落库模式：REAL（默认）/ TRIAL。
     *
     * <p>TRIAL 用于试问台：跑完整 RAG + 模型，但六个写库点全部关闭
     * （会话、消息、conversation_stats、knowledge_gap、grain_retrieve_log、
     * knowledge_case_retrieve_log），结果只由 {@code SkillTrialService} 写进试问表。</p>
     */
    private String persistMode;

    /**
     * 客户端消息幂等键（可选，建议 UUID）。同一会话内重试/双击复用同一个值，
     * 后端据此跳过重复落库；已有 AI 回复时直接重放，不再调用模型。
     */
    private String clientMessageId;

    /**
     * 试问/回放场景的显式多轮历史（role + content）。
     *
     * <p>正常对话从 skill_message 读历史；试问消息在 skill_trial_message，
     * 由 {@code SkillTrialService} 组装后注入。{@code @JsonIgnore} 防止客户端伪造历史。</p>
     */
    @JsonIgnore
    private java.util.List<java.util.Map<String, String>> trialHistory;

    /** 是否试问通道 —— 不落任何业务表，只由试问服务写 skill_trial_* */
    public boolean isTrialPersist() {
        return "TRIAL".equalsIgnoreCase(persistMode);
    }
}
