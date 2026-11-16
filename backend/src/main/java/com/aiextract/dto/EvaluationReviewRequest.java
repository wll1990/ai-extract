package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 单题人工复核请求 DTO。
 *
 * <p>{@code action}：adjust（改分）/ undetermined（无法判断）/ confirm（确认 AI 结论）。
 * 理由必填，AI 原分由服务端从执行记录读取，客户端不能覆盖。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationReviewRequest {

    /** 动作：adjust/undetermined/confirm */
    @NotBlank(message = "复核动作不能为空")
    private String action;

    /** 复核理由（必填） */
    @NotBlank(message = "复核理由不能为空")
    private String reason;

    /**
     * 人工改分（JSON 字符串，仅 adjust 需要）。
     *
     * <p>必须含 {@code total_score}（0-100）；服务端据此重算业务结论，其余字段原样留档。</p>
     */
    private String adjustedScore;
}
