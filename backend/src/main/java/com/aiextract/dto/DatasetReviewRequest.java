package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 题集审核请求 DTO。
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetReviewRequest {

    /** 审核动作：approve/reject */
    @NotBlank(message = "审核动作不能为空")
    private String action;

    /** 退回理由（reject 时必填，service 校验） */
    private String reason;
}
