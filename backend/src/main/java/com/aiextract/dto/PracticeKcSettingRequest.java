package com.aiextract.dto;

import jakarta.validation.constraints.NotNull;

public record PracticeKcSettingRequest(@NotNull Boolean enabled) {
}
