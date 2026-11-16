package com.aiextract.dto;

import jakarta.validation.constraints.Size;

public record CoachRequestCreateRequest(
        @Size(max = 500) String message,
        Boolean shareTranscript
) {
}
