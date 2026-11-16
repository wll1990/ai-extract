package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReviewMessageView(UUID id, String role, String content, LocalDateTime createdAt) {
}
