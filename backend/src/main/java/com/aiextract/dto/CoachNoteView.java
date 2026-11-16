package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CoachNoteView(UUID id, String coachName, String content, LocalDateTime createdAt) {
}
