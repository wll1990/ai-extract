package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 评测题目创建/编辑请求 DTO。
 *
 * <p>必填仅 question。expectedPoints/forbiddenPoints/citationRequirement/scoringRule/retrievalExpected
 * 均为 JSON 字符串（照 IndustryCaseRequest 的 JSONB 字段惯例）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationItemRequest {

    /** 题干/输入 */
    @NotBlank(message = "题干不能为空")
    private String question;

    /** 固定上下文（可比较性） */
    private String context;

    /** 预期要点（JSON 字符串） */
    private String expectedPoints;

    /** 禁止项（JSON 字符串） */
    private String forbiddenPoints;

    /** 引用要求（JSON 字符串） */
    private String citationRequirement;

    /** 适用场景 */
    private String sceneTag;

    /** 金标依据/出处 */
    private String goldBasis;

    /** 评分规则（JSON 字符串） */
    private String scoringRule;

    /** 检索期望（JSON 字符串） */
    private String retrievalExpected;

    /** 题序 */
    private Integer orderNo;
}
