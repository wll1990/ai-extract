package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 培训计划创建/更新请求 DTO。
 *
 * <p>{@code expectedVersion} 仅在更新时使用：与计划当前版本号不一致返回 409，
 * 防止两个管理员同时编辑草稿互相覆盖。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingPlanRequest {

    /** 计划名 */
    @NotBlank(message = "计划名不能为空")
    @Size(max = 255, message = "计划名不能超过 255 字")
    private String name;

    /** 描述 */
    private String description;

    /** 期望的当前版本号（乐观锁，更新时可选） */
    private Integer expectedVersion;
}
