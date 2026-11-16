package com.aiextract.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 同一客户的上一次复盘摘要 —— 用于「上次承诺的动作做了吗、客户信号变了吗」的对比。
 */
public record PreviousReviewView(
        UUID sessionId,
        LocalDateTime createdAt,
        String customerName,
        String topic,
        String summary,
        JsonNode customerSignals,
        List<PreviousTaskView> tasks
) {
    public record PreviousTaskView(
            UUID id,
            String title,
            String channel,
            LocalDateTime dueAt,
            String status,
            LocalDateTime completedAt
    ) {
    }
}
