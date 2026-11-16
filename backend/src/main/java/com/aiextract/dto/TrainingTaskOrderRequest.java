package com.aiextract.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * 任务排序请求 DTO —— 必须提交该版本的**完整**任务 ID 集合，
 * 缺项或多出未知 ID 一律 409，避免前端漏传导致顺序被静默丢弃。
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingTaskOrderRequest {

    /** 按目标顺序排列的完整任务 ID 列表 */
    @NotEmpty(message = "任务 ID 列表不能为空")
    private List<UUID> taskIds;
}
