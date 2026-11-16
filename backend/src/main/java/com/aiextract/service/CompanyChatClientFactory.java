package com.aiextract.service;

import com.aiextract.config.TokenAwareChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

/**
 * 用企业自己的 Base URL / Key 创建独立 ChatClient。
 *
 * <p>企业模型固定 DeepSeek 兼容协议；Embedding/STT 不走这里。</p>
 */
@Component
@RequiredArgsConstructor
public class CompanyChatClientFactory {

    private final TokenUsageService usage;

    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String model;

    @Value("${spring.ai.openai.chat.options.max-tokens:8192}")
    private int maxTokens;

    @Value("${spring.ai.openai.chat.options.temperature:0.1}")
    private double temperature;

    /** 兼容旧调用：使用 OpenAI 兼容默认路径与平台输出上限 */
    public ChatClient create(UUID companyId, String baseUrl, String apiKey, String modelName) {
        return create(companyId, baseUrl, apiKey, modelName, "/v1/chat/completions", maxTokens);
    }

    /**
     * 按厂商目录创建客户端：completionsPath 与输出上限来自 ProviderCatalog，
     * 避免 DashScope/自定义端点拼错路径、或小模型输出上限被全局值撑爆。
     */
    public ChatClient create(UUID companyId, String baseUrl, String apiKey, String modelName,
                             String completionsPath, int maxOutputTokens) {
        OpenAiApi api = new OpenAiApi(baseUrl, apiKey, completionsPath, "/v1/embeddings",
                RestClient.builder(), WebClient.builder(),
                RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .maxTokens(maxOutputTokens)
                .temperature(temperature)
                .build();
        OpenAiChatModel chatModel = new OpenAiChatModel(api, options);
        return ChatClient.builder(new TokenAwareChatModel(chatModel, usage, modelName, companyId))
                .defaultOptions(options)
                .build();
    }
}
