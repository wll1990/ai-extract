package com.aiextract.dto;

import jakarta.validation.constraints.Size;

public record TaskEscalateRequest(@Size(max = 500) String reason) {
}
