package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 撤销认证请求：理由必填，写入认证记录并保留历史。
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingRevokeCertificationRequest {

    @NotBlank(message = "撤销理由不能为空")
    @Size(max = 1000, message = "撤销理由不能超过 1000 字")
    private String reason;
}
