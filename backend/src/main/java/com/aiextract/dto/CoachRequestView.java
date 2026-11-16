package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CoachRequestView(
        UUID id,
        UUID sessionId,
        String reviewerName,
        String coachName,
        String customerName,
        String topic,
        String message,
        String status,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        LocalDateTime readAt,
        LocalDateTime repliedAt,
        String replyContent
) {
}
