package com.aiextract.service;

import com.aiextract.config.ProviderCatalog;
import com.aiextract.config.TokenAwareChatModel;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.http.HttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
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
    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallbackProvider> toolCallbackProviders;

    @Value("${spring.ai.openai.chat.model:deepseek-chat}")
    private String model;

    @Value("${spring.ai.openai.chat.max-tokens:8192}")
    private int maxTokens;

    @Value("${spring.ai.openai.chat.temperature:0.1}")
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
        // 2.0 起 OpenAI SDK 自行追加 /chat/completions，路径前缀必须折进 baseUrl
        String chatBaseUrl = ProviderCatalog.resolveChatBaseUrl(baseUrl, completionsPath);

        HttpClient httpClient = SpringAiOpenAiHttpClient.builder()
                .timeout(Duration.ofSeconds(180))
                .build();
        ClientOptions clientOptions = ClientOptions.builder()
                .httpClient(httpClient)
                .baseUrl(chatBaseUrl)
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(180))
                .maxRetries(1)
                .build();
        OpenAIClient openAiClient = new OpenAIClientImpl(clientOptions);

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .maxTokens(maxOutputTokens)
                .temperature(temperature)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .toolCallingManager(toolCallingManager)
                .options(options)
                .build();
        ChatClient.Builder builder = ChatClient.builder(
                        new TokenAwareChatModel(chatModel, usage, modelName, companyId))
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(modelName)
                        .maxTokens(maxOutputTokens)
                        .temperature(temperature));
        if (!toolCallbackProviders.isEmpty()) {
            builder.defaultToolCallbacks(
                    toolCallbackProviders.toArray(new ToolCallbackProvider[0]));
        }
        return builder.build();
    }
}
