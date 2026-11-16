package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CoachLedgerRow(
        UUID sessionId,
        String reviewerName,
        String customerName,
        String topic,
        String summary,
        String reportStatus,
        String reportError,
        String status,
        boolean transcriptShared,
        LocalDateTime createdAt
) {
}
