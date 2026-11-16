package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;

public record StatusTransitionRequest(@NotBlank String status) {
}
