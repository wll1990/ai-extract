package com.aiextract.config;

import com.aiextract.service.ChatClientResolver;
import com.aiextract.service.TenantChatClientProxy;
import com.aiextract.service.TokenUsageService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * @author AI Extract Team
 */
@Configuration
public class AiConfig {

    @Value("${spring.ai.openai.chat.model:deepseek-chat}")
    private String modelName;

    @Value("${spring.ai.openai.chat.max-tokens:8192}")
    private int maxTokens;

    @Value("${spring.ai.openai.chat.temperature:0.7}")
    private double temperature;

    /** 平台 ChatClient：未配置企业 Key / 无企业上下文时使用 */
    @Bean("platformChatClient")
    public ChatClient platformChatClient(ChatModel chatModel, TokenUsageService tokenUsageService,
                                         java.util.List<ToolCallbackProvider> toolCallbackProviders) {
        ChatClient.Builder builder = ChatClient.builder(
            new TokenAwareChatModel(chatModel, tokenUsageService, modelName, null)
        ).defaultOptions(OpenAiChatOptions.builder()
                .model(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
        );
        if (!toolCallbackProviders.isEmpty()) {
            builder.defaultToolCallbacks(
                    toolCallbackProviders.toArray(new ToolCallbackProvider[0]));
        }
        return builder.build();
    }

    /** 租户代理：所有注入 ChatClient 的代码无感切换到企业 Key */
    @Bean("chatClient")
    @Primary
    public ChatClient chatClient(ChatClientResolver resolver) {
        return TenantChatClientProxy.of(resolver);
    }

    /** Token 记录异步线程池 */
    @Bean("tokenLogExecutor")
    public Executor tokenLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("token-log-");
        executor.setRejectedExecutionHandler(
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new TokenContext.Decorator());
        executor.initialize();
        return executor;
    }

    /** 异步线程池（embedding + 清洗管道） */
    @Bean("embeddingExecutor")
    public Executor embeddingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("embedding-");
        executor.setRejectedExecutionHandler(
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new TokenContext.Decorator());
        executor.initialize();
        return executor;
    }

    /** 文件解析线程池 — HTTP 调 AI 服务解析，秒级返回，可适度并发 */
    @Bean("parseExecutor")
    public Executor parseExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("parse-");
        executor.setRejectedExecutionHandler(
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new TokenContext.Decorator());
        executor.initialize();
        return executor;
    }

    /** RAG 检索线程池 — Dense+Sparse 并行查询，毫秒级 JDBC，高并发 */
    @Bean("ragRetrievalExecutor")
    public Executor ragRetrievalExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("rag-");
        executor.setRejectedExecutionHandler(
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new TokenContext.Decorator());
        executor.initialize();
        return executor;
    }

    /** 素材清洗线程池 — 多层 AI 调用，分钟级，少而精 */
    @Bean("cleanExecutor")
    public Executor cleanExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("clean-");
        executor.setRejectedExecutionHandler(
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new TokenContext.Decorator());
        executor.initialize();
        return executor;
    }

    /**
     * 颗粒抽取线程池 — 与 cleanExecutor 分离。
     * 清洗任务本身跑在 cleanExecutor 上，chunk 抽取若再提交到同一池并 join，
     * 池满时会互相等待形成自锁（M16）。
     */
    @Bean("grainExtractExecutor")
    public Executor grainExtractExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(40);
        executor.setThreadNamePrefix("grain-extract-");
        executor.setRejectedExecutionHandler(
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new TokenContext.Decorator());
        executor.initialize();
        return executor;
    }

    /**
     * 评测候选生成线程池 — 逐素材 AI 出题，独立于销冠清洗管道，避免相互抢资源。
     *
     * <p>拒绝策略必须是 AbortPolicy：CallerRunsPolicy 会在队列满时让 AI 调用回落到
     * HTTP 请求线程，违反「请求线程内禁止 AI 调用」的红线。</p>
     */
    @Bean("generationExecutor")
    public Executor generationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("eval-gen-");
        executor.setRejectedExecutionHandler(
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(new TokenContext.Decorator());
        executor.initialize();
        return executor;
    }

    /** 内容工厂生成线程池 — 知识/案例/考题/派生，和评测候选生成隔离 */
    @Bean("contentGenerationExecutor")
    public Executor contentGenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("content-gen-");
        executor.setRejectedExecutionHandler(
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(new TokenContext.Decorator());
        executor.initialize();
        return executor;
    }
}
