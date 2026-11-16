package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保存企业 AI Key 配置。apiKey 为空表示不修改已存 Key。
 */
public record CompanyAiConfigRequest(
        @NotBlank @Size(max = 300) String baseUrl,
        @Size(max = 300) String apiKey,
        Boolean fallbackEnabled,
        /** deepseek / openai / qwen / moonshot / custom；为空时沿用已存配置或 deepseek */
        @Size(max = 50) String provider,
        /** 模型名，如 deepseek-chat；为空时用厂商默认模型 */
        @Size(max = 100) String modelName
) {
}
