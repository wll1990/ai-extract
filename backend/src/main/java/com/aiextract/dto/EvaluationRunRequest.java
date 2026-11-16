package com.aiextract.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * 创建评测批次请求 DTO。
 *
 * <p>{@code datasetVersion} 省略时取题集当前版本，但仍必须是已发布（冻结）版本；
 * {@code idempotencyKey} 由前端生成，同企业内唯一，用于防双击/重放建重复批次。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationRunRequest {

    /** 题集 ID */
    @NotNull(message = "题集不能为空")
    private UUID datasetId;

    /** 题集版本号（省略=当前版本） */
    private Integer datasetVersion;

    /** 被评测分身 ID */
    @NotNull(message = "分身不能为空")
    private UUID skillId;

    /** 用途：regression/acceptance，必须与题集用途一致；省略时取题集用途 */
    private String purpose;

    /** 模型配置快照（JSON 字符串，可空；空则记录服务端默认配置） */
    private String modelConfig;

    /** 幂等键（同企业唯一，建议前端每次点击生成一个） */
    private String idempotencyKey;
}
