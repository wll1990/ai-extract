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
 * 补训请求：指向某一失败轮的记录，追加下一轮。
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingRemediationRequest {

    /** 目标记录（必须是该任务最新且 failed 的一轮） */
    @NotNull(message = "补训目标记录不能为空")
    private UUID recordId;

    /** 补训原因（学员可见） */
    @NotBlank(message = "补训原因不能为空")
    @Size(max = 1000, message = "补训原因不能超过 1000 字")
    private String reason;

    /** 可选新截止；分配已过期时必填 */
    private LocalDateTime deadline;
}
