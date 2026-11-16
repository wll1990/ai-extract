package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 知识库条目创建/编辑请求 DTO。
 *
 * <p>必填：title/category/sourceType/content/expireAt（失效时间由人工填，
 * AI 提取不出来）。effectiveAt/source/status 可选。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeEntryRequest {

    /** 标题 */
    @NotBlank(message = "标题不能为空")
    private String title;

    /** 内容类型：compliance/price/product/policy/competitor/terminology/methodology/industry_intro */
    @NotBlank(message = "分类不能为空")
    private String category;

    /** 来源类型：public/platform/deidentified/tenant */
    @NotBlank(message = "来源类型不能为空")
    private String sourceType;

    /** 知识正文 */
    @NotBlank(message = "内容不能为空")
    private String content;

    /** 失效时间（必填，派生过期态） */
    @NotNull(message = "失效时间不能为空")
    private LocalDateTime expireAt;

    /** 生效时间（可选） */
    private LocalDateTime effectiveAt;

    /** 来源出处（版权标注，公共知识必填） */
    private String source;

    /** 状态：draft/pending/active（服务端校验合法值） */
    private String status;
}
