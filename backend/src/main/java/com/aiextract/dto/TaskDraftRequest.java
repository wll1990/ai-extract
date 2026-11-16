package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record TaskDraftRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 20) String channel,
        LocalDateTime dueAt,
        @Size(max = 2000) String content
) {
}
