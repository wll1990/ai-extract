package com.aiextract.dto;

import java.util.UUID;

public record ReviewSegmentView(UUID id, int seq, String speaker, String text) {
}
