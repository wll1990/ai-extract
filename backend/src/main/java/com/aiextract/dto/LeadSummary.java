package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record LeadSummary(
        UUID id,
        String name,
        String ownerId,
        String ownerName,
        String status,
        String source,
        UUID sourceReviewId,
        String needSummary,
        LocalDateTime nextFollowUpAt,
        LocalDateTime lastActivityAt,
        LocalDateTime updatedAt
) {
}
