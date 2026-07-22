package com.aiextract.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 模型调用适配器
 *
 * 统一出口：所有 LLM 调用经此适配器，记录完整的请求和响应数据。
  * @author AI Extract Team
 */
@Slf4j
@Component
public class ChatStreamAdapter {

    private final ChatClient chatClient;

    public ChatStreamAdapter(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 非流式调用 — 所有同步 LLM 调用的统一出口。
     *
     * 每次调用记录 REQ（prompt 前 500 字）和 RSP（响应全文 + 耗时），
     * 确保萃取管线每一层都有完整的输入/输出可追溯。
     */
    public String chat(String prompt) {
        long t0 = System.currentTimeMillis();
        log.debug("LLM REQ chars={} preview={}",
            prompt.length(),
            prompt.length() > 500
                ? prompt.substring(0, 500).replace("\n", "\\n") + "..."
                : prompt.replace("\n", "\\n"));

        String content = chatClient.prompt().user(prompt).call().content();
        long elapsed = System.currentTimeMillis() - t0;

        if (content != null) {
            log.info("LLM RSP chars={} time={}ms preview={}",
                content.length(), elapsed,
                content.length() > 300
                    ? content.substring(0, 300).replace("\n", "\\n") + "..."
                    : content.replace("\n", "\\n"));
        } else {
            log.warn("LLM RSP null time={}ms", elapsed);
        }
        return content;
    }

    /**
     * 将消息列表转为 ChatClient 流式调用，输出格式兼容旧 SSE 事件。
     *
     * 每轮调用记录：
     * - REQ: 发送给大模型的 prompt（截断至 500 字符防日志爆炸）
     * - RSP: 流式返回的每个 chunk（截断至 200 字符），合并后的完整响应
     */
    public Flux<Map<String, Object>> chatStream(List<Map<String, String>> messages,
                                                 Map<String, Object> context) {
        // 将消息列表格式化为单个 prompt（去重连续相同消息）
        StringBuilder prompt = new StringBuilder();
        String prevContent = null;
        String prevRole = null;
        for (Map<String, String> msg : messages) {
            String role = msg.get("role");
            String content = msg.get("content");
            if (content == null) {
                continue;
            }
            // 跳过连续相同的消息（同角色+同内容=重复发送）
            if (content.equals(prevContent) && role != null && role.equals(prevRole)) {
                log.debug("LLM 跳过重复消息 role={} len={}", role, content.length());
                continue;
            }
            prevContent = content;
            prevRole = role;
            if ("system".equals(role)) {
                prompt.append("指令：").append(content).append("\n\n");
            } else if ("user".equals(role)) {
                prompt.append("用户：").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                prompt.append("助手：").append(content).append("\n");
            }
        }
        prompt.append("\n助手：");

        String fullPrompt = prompt.toString();
        // 日志：发送给大模型的请求
        log.debug("LLM REQ chars={} preview={}",
            fullPrompt.length(),
            fullPrompt.length() > 500
                ? fullPrompt.substring(0, 500).replace("\n", "\\n") + "..."
                : fullPrompt.replace("\n", "\\n"));
        // 完整 prompt 日志，用于诊断提示词大小和内容
        log.info("LLM FULL_PROMPT chars={} \n{}", fullPrompt.length(), fullPrompt);

        AtomicInteger chunkCount = new AtomicInteger(0);
        AtomicLong totalChars = new AtomicLong(0);
        StringBuilder fullResponse = new StringBuilder();

        return chatClient.prompt()
            .user(fullPrompt)
            .stream()
            .chatResponse()
            .map(response -> {
                String content = response.getResult().getOutput().getContent();
                if (content != null) {
                    chunkCount.incrementAndGet();
                    totalChars.addAndGet(content.length());
                    fullResponse.append(content);
                    // 日志：每个 chunk 的内容（长 chunk 截断）
                    log.debug("LLM CHUNK #{} len={} content={}",
                        chunkCount.get(), content.length(),
                        content.length() > 200
                            ? content.substring(0, 200).replace("\n", "\\n") + "..."
                            : content.replace("\n", "\\n"));
                }
                return Map.<String, Object>of("type", "content", "content", content);
            })
            .doOnComplete(() -> {
                // 日志：完整响应摘要
                String responsePreview = fullResponse.length() > 300
                    ? fullResponse.substring(0, 300).replace("\n", "\\n") + "..."
                    : fullResponse.toString().replace("\n", "\\n");
                log.info("LLM RSP chunks={} chars={} preview={}",
                    chunkCount.get(), totalChars.get(), responsePreview);
            })
            .doOnError(error -> log.error("LLM ERR {}", error.getMessage()))
            .concatWithValues(Map.of("type", "done"));
    }
}
