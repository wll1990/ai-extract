package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CoachNoteCreateRequest(@NotBlank @Size(max = 2000) String content) {
}
