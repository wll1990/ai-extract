package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 新建培训批次请求 DTO。
 *
 * <p>批次必须绑定**已发布**的计划版本：学员分配到的是冻结快照，后续计划迭代不影响他们。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingCohortRequest {

    /** 培训计划 ID */
    @NotNull(message = "计划不能为空")
    private UUID planId;

    /** 绑定的计划版本（必须是已发布版本） */
    @NotNull(message = "计划版本不能为空")
    private Integer planVersion;

    /** 批次名 */
    @NotBlank(message = "批次名不能为空")
    @Size(max = 255, message = "批次名不能超过 255 字")
    private String name;

    /** 导师/复核人（可选；该用户只能复核自己带的批次） */
    private UUID mentorId;

    /** 批次截止时间（可选） */
    private LocalDateTime deadline;
}
