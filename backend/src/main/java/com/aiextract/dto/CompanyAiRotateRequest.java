package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyAiRotateRequest(
        @NotBlank @Size(min = 8, max = 300) String apiKey
) {
}
