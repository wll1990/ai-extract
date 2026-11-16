package com.aiextract.dto;

import java.util.List;
import java.util.UUID;

public record CoachWorkspaceView(
        UUID sessionId,
        String reviewerName,
        String customerName,
        String topic,
        boolean transcriptShared,
        ReviewReportView report,
        List<ReviewSegmentView> segments,
        List<CoachNoteView> notes,
        String requestStatus,
        String requestMessage
) {
}
