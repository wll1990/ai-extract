package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record TaskView(
        UUID id,
        UUID leadId,
        UUID reviewId,
        UUID ownerId,
        String ownerName,
        String title,
        String channel,
        String content,
        LocalDateTime dueAt,
        String status,
        LocalDateTime completedAt,
        LocalDateTime escalatedAt
) {
}
