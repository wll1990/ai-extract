package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 行业案例创建/编辑请求 DTO。
 *
 * <p>必填仅 title；其余字段可选。customerProfile/dialogueQuote 为 JSON 字符串
 * （对应实体 JSONB 列），前端序列化后传入。expireAt 可选（案例可长期有效）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndustryCaseRequest {

    /** 案例标题 */
    @NotBlank(message = "标题不能为空")
    private String title;

    /** 客户画像（JSON 字符串：行业/规模/角色） */
    private String customerProfile;

    /** 漏斗阶段：线索/首触/需求/方案/异议/报价/追单/成交/复购 */
    private String funnelStage;

    /** 关联场景标签 */
    private String sceneTag;

    /** 客户原话（JSON 字符串数组） */
    private String dialogueQuote;

    /** 核心异议 */
    private String objection;

    /** 情绪变化 */
    private String emotionChange;

    /** 关键动作 */
    private String keyActions;

    /** 话术 */
    private String scripts;

    /** 推进节点 */
    private String advanceNodes;

    /** 结果：win/loss/delay */
    private String result;

    /** 可复用点 */
    private String reusablePoints;

    /** 失败点 */
    private String failurePoints;

    /** 来源出处 */
    private String source;

    /** 生效时间（可选） */
    private LocalDateTime effectiveAt;

    /** 失效时间（可选） */
    private LocalDateTime expireAt;

    /** 状态：draft/pending/active（服务端校验合法值） */
    private String status;
}
