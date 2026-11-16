package com.aiextract.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReviewReportView(
        UUID id,
        int version,
        String status,
        String summary,
        JsonNode payload,
        String errorMessage,
        String modelName,
        LocalDateTime queuedAt,
        LocalDateTime startedAt,
        LocalDateTime generatedAt
) {
}
