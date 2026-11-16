package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * AI 生成候选任务请求 DTO。
 *
 * <p>从知识/案例库中挑选若干条目，异步生成候选评测题目写入草稿版本。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationGenerationRequest {

    /** 来源类型：knowledge/case */
    @NotBlank(message = "来源类型不能为空")
    @Pattern(regexp = "knowledge|case", message = "来源类型仅支持 knowledge/case")
    private String sourceType;

    /** 来源条目 ID 列表（至少一个） */
    @NotEmpty(message = "至少选择一个来源条目")
    private List<UUID> sourceIds;
}
