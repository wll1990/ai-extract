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

/**
 * 延长分配截止请求。
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingDeadlineExtendRequest {

    @NotNull(message = "新截止不能为空")
    private LocalDateTime deadline;

    @NotBlank(message = "延期理由不能为空")
    @Size(max = 1000, message = "延期理由不能超过 1000 字")
    private String reason;
}
