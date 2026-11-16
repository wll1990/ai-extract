package com.aiextract.dto;

/**
 * 企业 AI 配置视图。永远不包含明文 Key。
 */
public record CompanyAiConfigView(
        boolean configured,
        String provider,
        String model,
        String baseUrl,
        String keyLast4,
        String status,
        boolean fallbackEnabled,
        boolean platformFallbackAvailable,
        long inputTokensThisMonth,
        long outputTokensThisMonth
) {
}
