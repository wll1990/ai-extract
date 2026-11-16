package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record LeadUpdateRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String needSummary,
        LocalDateTime nextFollowUpAt
) {
}
