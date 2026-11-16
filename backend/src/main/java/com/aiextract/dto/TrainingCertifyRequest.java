package com.aiextract.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 认证确认请求 DTO —— 备注可选。
 *
 * <p>认证门槛（必修全过、非本人、数据范围）全部由服务端校验，不读取请求里的任何状态字段。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingCertifyRequest {

    /** 确认备注（可选） */
    @Size(max = 1000, message = "备注不能超过 1000 字")
    private String note;
}
