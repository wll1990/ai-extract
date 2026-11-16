package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 知识 AI 整理请求 DTO。
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SuggestRevisionRequest {

    /** 人工修改指令 */
    @NotBlank(message = "修改指令不能为空")
    private String instruction;
}
