package com.aiextract.dto;

import java.util.List;
import java.util.UUID;

public record ReviewDetailView(
        ReviewSessionView session,
        List<ReviewSegmentView> segments,
        LeadSummary lead
) {
    public record LeadSummary(UUID id, String name, String status) {
    }
}
