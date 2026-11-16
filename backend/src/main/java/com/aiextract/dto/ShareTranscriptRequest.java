package com.aiextract.dto;

import jakarta.validation.constraints.NotNull;

public record ShareTranscriptRequest(@NotNull Boolean shareTranscriptWithCoach) {
}
