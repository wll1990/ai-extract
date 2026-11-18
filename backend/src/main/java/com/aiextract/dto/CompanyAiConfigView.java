package com.aiextract.dto;

import java.time.LocalDateTime;

/**
 * 企业 AI 配置视图。永远不包含明文 Key。
 *
 * <p>{@code effectiveMode} 是给管理员看的结论，避免自己在脑子里组合
 * 「是否配置 / 是否启用 / 是否回退」三个布尔值：</p>
 * <ul>
 *   <li>{@code company_key}：企业 Key 已启用，当前对话走企业 Key</li>
 *   <li>{@code platform_key}：未配置，或已停用且允许回退，当前走平台 Key</li>
 *   <li>{@code unavailable}：已停用且关闭回退，AI 调用会直接失败</li>
 * </ul>
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
        long outputTokensThisMonth,
        String effectiveMode,
        String effectiveModel,
        String effectiveBaseUrl,
        LocalDateTime updatedAt,
        String updatedByName
) {
}
