package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record LeadActivityView(
        UUID id,
        String type,
        String content,
        String actorName,
        String sourceType,
        UUID sourceId,
        LocalDateTime occurredAt
) {
}
