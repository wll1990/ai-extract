package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LeadConvertRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 100) String contactName,
        @Size(max = 50) String contactPhone,
        @Size(max = 2000) String needSummary,
        Boolean force
) {
}
