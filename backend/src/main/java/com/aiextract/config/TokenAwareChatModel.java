package com.aiextract.config;

import com.aiextract.service.TokenUsageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ChatModel 装饰器 — 拦截 call/stream，提取 token 用量异步入库。
 */
@Slf4j
public class TokenAwareChatModel implements ChatModel {

    private final ChatModel delegate;
    private final TokenUsageService tokenUsageService;
    private final String modelName;

    public TokenAwareChatModel(ChatModel delegate, TokenUsageService tokenUsageService, String modelName) {
        this.delegate = delegate;
        this.tokenUsageService = tokenUsageService;
        this.modelName = modelName;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        logUsage(response.getMetadata().getUsage());
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        return delegate.stream(prompt)
            .doOnNext(resp -> {
                Usage usage = resp.getMetadata() != null ? resp.getMetadata().getUsage() : null;
                if (usage != null && usage.getTotalTokens() > 0) {
                    lastUsage.set(usage);
                }
            })
            .doOnComplete(() -> logUsage(lastUsage.get()));
    }

    private void logUsage(Usage usage) {
        if (usage == null) return;
        UUID userId = TokenContext.get();
        tokenUsageService.log(userId, "CHAT", modelName,
            (int) (long) usage.getPromptTokens(),
            (int) (long) usage.getGenerationTokens());
    }

}
