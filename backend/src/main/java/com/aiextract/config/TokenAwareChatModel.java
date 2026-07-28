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
 * ChatModel 装饰器 — 拦截 call/stream，自己统计 token 用量的统一出口。
 *
 * <p><b>策略</b>：不依赖 LLM API 返回的 Usage（流式模式下不可靠），
 * 而是从 Prompt 对象（发前）和响应内容（收时累积）自己计数字符 → 估算 token。
 * API 真实 Usage 有则覆盖估算值。字符计数字段（prompt_chars / completion_chars）
 * 始终落库，便于后续校准估算系数。</p>
 *
 * <p>大 prompt 审计：当 prompt 超过 10000 字符时输出 WARN 日志，帮助发现
 * 提示词膨胀、RAG 注入过多颗粒等问题。</p>
 */
@Slf4j
public class TokenAwareChatModel implements ChatModel {

    private static final int PROMPT_WARN_THRESHOLD = 10_000;

    /** 中文为主场景：1 token ≈ 1.5-2 字符，取保守值 2 避免低估成本 */
    private static final int CHARS_PER_TOKEN = 2;

    private final ChatModel delegate;
    private final TokenUsageService tokenUsageService;
    private final String modelName;

    public TokenAwareChatModel(ChatModel delegate, TokenUsageService tokenUsageService, String modelName) {
        this.delegate = delegate;
        this.tokenUsageService = tokenUsageService;
        this.modelName = modelName;
    }

    // ============================================================
    // 同步调用 — Usage 通常可靠，仍计算 prompt_chars 做基准
    // ============================================================

    @Override
    public ChatResponse call(Prompt prompt) {
        int promptChars = countPromptChars(prompt);
        auditPromptSize(promptChars);

        ChatResponse response = delegate.call(prompt);
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;

        // 同步调用可从响应体直接拿到完整输出内容
        String responseContent = response.getResult() != null && response.getResult().getOutput() != null
                ? response.getResult().getOutput().getContent() : "";
        int completionChars = responseContent != null ? responseContent.length() : 0;

        int inputTokens;
        int outputTokens;

        if (usage != null && usage.getTotalTokens() > 0) {
            inputTokens = (int) (long) usage.getPromptTokens();
            outputTokens = (int) (long) usage.getGenerationTokens();
        } else {
            inputTokens = promptChars / CHARS_PER_TOKEN;
            outputTokens = completionChars / CHARS_PER_TOKEN;
        }

        log.info("LLM call() done promptChars={} completionChars={} inputTokens={} outputTokens={} model={}",
                promptChars, completionChars, inputTokens, outputTokens, modelName);
        logUsage(inputTokens, outputTokens, promptChars, completionChars);
        return response;
    }

    // ============================================================
    // 流式调用 — 核心：自己累积响应 + 计数，不依赖 API Usage
    // ============================================================

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        int promptChars = countPromptChars(prompt);
        auditPromptSize(promptChars);

        AtomicReference<Usage> lastUsage = new AtomicReference<>();
        StringBuilder fullResponse = new StringBuilder();

        return delegate.stream(prompt)
                .doOnNext(resp -> {
                    // 累积响应内容（用于 completion_chars）
                    if (resp.getResult() != null && resp.getResult().getOutput() != null) {
                        String content = resp.getResult().getOutput().getContent();
                        if (content != null) {
                            fullResponse.append(content);
                        }
                    }
                    // 仍然尝试从 API 拿真实 Usage（有则后续覆盖估算）
                    Usage usage = resp.getMetadata() != null ? resp.getMetadata().getUsage() : null;
                    if (usage != null && usage.getTotalTokens() > 0) {
                        lastUsage.set(usage);
                    }
                })
                .doOnComplete(() -> {
                    int completionChars = fullResponse.length();
                    int inputTokens;
                    int outputTokens;

                    Usage usage = lastUsage.get();
                    if (usage != null && usage.getTotalTokens() > 0 && usage.getPromptTokens() > 0) {
                        // API 返回了完整 Usage → 用真实值，chars 仍落库做基准
                        inputTokens = (int) (long) usage.getPromptTokens();
                        outputTokens = (int) (long) usage.getGenerationTokens();
                    } else {
                        // API 未返回 Usage（流式默认不带）→ 从字符数估算
                        inputTokens = promptChars / CHARS_PER_TOKEN;
                        outputTokens = completionChars / CHARS_PER_TOKEN;
                    }

                    log.info("LLM stream() done promptChars={} completionChars={} inputTokens={} outputTokens={} model={}",
                            promptChars, completionChars, inputTokens, outputTokens, modelName);
                    logUsage(inputTokens, outputTokens, promptChars, completionChars);
                })
                .doOnError(err -> {
                    // 流中断：至少把 prompt 统计落库，completion 按已累积的算
                    int completionChars = fullResponse.length();
                    int inputTokens = promptChars / CHARS_PER_TOKEN;
                    int outputTokens = completionChars / CHARS_PER_TOKEN;
                    log.warn("LLM stream() error promptChars={} completionChars={} err={}",
                            promptChars, completionChars, err.getMessage());
                    logUsage(inputTokens, outputTokens, promptChars, completionChars);
                });
    }

    // ============================================================
    // 内部方法
    // ============================================================

    /** 从 Prompt 对象中提取所有消息的字符总数 */
    private int countPromptChars(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) {
            return 0;
        }
        return prompt.getInstructions().stream()
                .mapToInt(msg -> msg.getContent() != null ? msg.getContent().length() : 0)
                .sum();
    }

    /** 大 prompt 审计日志 */
    private void auditPromptSize(int promptChars) {
        if (promptChars > PROMPT_WARN_THRESHOLD) {
            log.warn("⚠️ 大 Prompt 检测 promptChars={} > {} 阈值, model={}",
                    promptChars, PROMPT_WARN_THRESHOLD, modelName);
        }
        log.debug("LLM REQ promptChars={} model={}", promptChars, modelName);
    }

    /** 异步入库（失败不影响主流程） */
    private void logUsage(int inputTokens, int outputTokens, int promptChars, int completionChars) {
        UUID userId = TokenContext.get();
        if (userId == null) {
            log.debug("TokenContext 无 userId，跳过 token 统计");
            return;
        }
        tokenUsageService.log(userId, "CHAT", modelName,
                inputTokens, outputTokens, promptChars, completionChars);
    }
}
