package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 题集创建/编辑请求 DTO。
 *
 * <p>必填：name/purpose。version 用于乐观锁（更新时客户端带回所见版本，不一致返回 409）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationDatasetRequest {

    /** 题集名 */
    @NotBlank(message = "题集名不能为空")
    private String name;

    /** 用途：regression/acceptance/training */
    @NotBlank(message = "用途不能为空")
    private String purpose;

    /** 描述 */
    private String description;

    /** 乐观锁版本号（更新时必填，创建时忽略） */
    private Integer version;
}
