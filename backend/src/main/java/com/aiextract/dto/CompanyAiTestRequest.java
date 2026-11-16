package com.aiextract.dto;

import jakarta.validation.constraints.Size;

/**
 * 测试连通：两个字段都可空，空则用已存配置 / 平台默认。
 */
public record CompanyAiTestRequest(
        @Size(max = 300) String apiKey,
        @Size(max = 300) String baseUrl,
        @Size(max = 50) String provider,
        @Size(max = 100) String modelName
) {
}
