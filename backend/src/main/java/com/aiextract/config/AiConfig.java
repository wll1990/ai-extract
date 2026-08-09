package com.aiextract.config;

import com.aiextract.service.TokenUsageService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * @author AI Extract Team
 */
@Configuration
public class AiConfig {

    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String modelName;

    @Value("${spring.ai.openai.chat.options.max-tokens:8192}")
    private int maxTokens;

    @Value("${spring.ai.openai.chat.options.temperature:0.7}")
    private double temperature;

    /** Chat: DeepSeek（包装 ChatModel 自动记录 token，无循环依赖） */
    @Bean
    public ChatClient chatClient(ChatModel chatModel, TokenUsageService tokenUsageService) {
        return ChatClient.builder(
            new TokenAwareChatModel(chatModel, tokenUsageService, modelName)
        ).defaultOptions(OpenAiChatOptions.builder()
                .model(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .build()
        ).build();
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
}
