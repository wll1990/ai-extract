package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record LeadContactView(
        UUID id,
        String name,
        String phone,
        String role,
        boolean primary,
        String note,
        LocalDateTime createdAt
) {
}
