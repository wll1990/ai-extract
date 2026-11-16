package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * 复核请求 DTO —— 主管/导师对一次尝试给出最终结论。
 *
 * <p>{@code reason} 必填：退回时它就是学员的整改要求，不能空泛；
 * {@code adjustedScore} 可选，用于人工纠正 AI 分数（原分另存，不改历史）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingReviewRequest {

    /** pass / reject */
    @NotBlank(message = "复核动作不能为空")
    private String action;

    /** 复核理由（必填） */
    @NotBlank(message = "复核理由不能为空")
    @Size(max = 2000, message = "复核理由不能超过 2000 字")
    private String reason;

    /** 复核后分数（可选，原样存 adjusted_score） */
    private Map<String, Object> adjustedScore;
}
