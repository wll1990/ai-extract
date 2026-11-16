package com.aiextract.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 批量分配学员请求 DTO。
 *
 * <p>逐人返回「已分配 / 跳过 / 失败」，不只给一个总数：主管需要知道谁没分进去、为什么。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingBatchAssignRequest {

    /** 目标批次 */
    @NotNull(message = "批次不能为空")
    private UUID cohortId;

    /** 学员 ID 列表（必须是本企业有效账号） */
    @NotEmpty(message = "学员列表不能为空")
    @jakarta.validation.constraints.Size(max = 500, message = "单次最多分配 500 人")
    private List<UUID> learnerIds;

    /** 分配截止时间（省略则沿用批次截止时间） */
    private LocalDateTime deadline;
}
