package com.aiextract.dto;

import java.util.List;

public record LeadConvertResult(
        boolean created,
        LeadSummary lead,
        List<LeadCandidateView> duplicateCandidates
) {
}
