package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 企业 AI Key 配置历史行。不含任何 Key 明文/密文。
 */
public record CompanyAiConfigHistoryView(
        UUID id,
        String action,
        String provider,
        String model,
        String baseUrl,
        String keyLast4,
        boolean fallbackEnabled,
        UUID actorId,
        String actorName,
        LocalDateTime createdAt
) {
}
