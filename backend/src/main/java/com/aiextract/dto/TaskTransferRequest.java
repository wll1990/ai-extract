package com.aiextract.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record TaskTransferRequest(@NotNull UUID ownerId) {
}
