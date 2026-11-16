package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReviewSessionView(
        UUID id,
        UUID reviewerId,
        String reviewerName,
        UUID coachSkillId,
        String coachName,
        String coachTitle,
        String customerName,
        String topic,
        String status,
        int transcriptChars,
        boolean shareTranscriptWithCoach,
        UUID leadId,
        Long taskCount,
        LocalDateTime createdAt,
        ReviewReportView latestReport
) {
}
