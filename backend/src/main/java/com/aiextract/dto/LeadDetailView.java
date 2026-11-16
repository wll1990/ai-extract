package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.List;

public record LeadDetailView(
        LeadSummary summary,
        String needSummary,
        LocalDateTime nextFollowUpAt,
        LocalDateTime lastActivityAt,
        List<LeadContactView> contacts,
        List<LeadActivityView> activities,
        List<TaskView> tasks,
        LeadSourceReviewView sourceReview
) {
}
