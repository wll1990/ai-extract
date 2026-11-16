package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record LeadCandidateView(UUID id, String name, String ownerName, LocalDateTime createdAt) {
}
