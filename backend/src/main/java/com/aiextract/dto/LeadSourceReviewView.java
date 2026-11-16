package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record LeadSourceReviewView(
        UUID id,
        String customerName,
        String topic,
        String coachName,
        LocalDateTime createdAt
) {
}
