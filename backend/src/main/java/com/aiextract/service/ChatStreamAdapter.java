package com.aiextract.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;

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
            // token 预算预警：5000 字（≈7500 token）警告，8192 token 上限告警
            if (content.length() > 5000) {
                log.warn("LLM RSP 接近上限: chars={} (≈{} token), promptPreview={}",
                    content.length(), (int)(content.length() * 1.5),
                    prompt.length() > 200 ? prompt.substring(0, 200).replace("\n", "\\n") : prompt);
            }
        } else {
            log.warn("LLM RSP null time={}ms", elapsed);
        }
        return content;
    }

    /**
     * 非流式调用（system + user 分离）。
     *
     * <p>判分类场景必须用这个重载：规则与金标放 system，待评材料放 user，
     * 避免材料里的「指令」与系统规则混在同一条消息里执行。</p>
     */
    public String chat(String systemPrompt, String userPrompt) {
        long t0 = System.currentTimeMillis();
        log.debug("LLM REQ systemChars={} userChars={} userPreview={}",
            systemPrompt == null ? 0 : systemPrompt.length(),
            userPrompt == null ? 0 : userPrompt.length(),
            userPrompt == null ? "" : userPrompt.substring(0, Math.min(300, userPrompt.length()))
                .replace("\n", "\\n"));

        String content = chatClient.prompt()
            .system(systemPrompt == null ? "" : systemPrompt)
            .user(userPrompt == null ? "" : userPrompt)
            .call().content();
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
        // system 规则与用户/材料分离：材料里的「指令」不能覆盖系统规则
        StringBuilder systemPrompt = new StringBuilder();
        StringBuilder userPrompt = new StringBuilder();
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
                systemPrompt.append(content).append("\n\n");
            } else if ("user".equals(role)) {
                userPrompt.append("用户：").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                userPrompt.append("助手：").append(content).append("\n");
            }
        }
        userPrompt.append("\n助手：");

        String system = systemPrompt.toString();
        String fullPrompt = userPrompt.toString();
        // 日志：发送给大模型的请求
        log.debug("LLM REQ chars={} preview={}",
            fullPrompt.length(),
            fullPrompt.length() > 500
                ? fullPrompt.substring(0, 500).replace("\n", "\\n") + "..."
                : fullPrompt.replace("\n", "\\n"));
        // 复盘追问的 prompt 含客户隐私，默认不打印全文；排障时用 DEBUG 看截断预览
        log.debug("LLM REQ fullPrompt chars={} preview={}",
            fullPrompt.length(),
            fullPrompt.length() > 500
                ? fullPrompt.substring(0, 500).replace("\n", "\\n") + "..."
                : fullPrompt.replace("\n", "\\n"));

        AtomicInteger chunkCount = new AtomicInteger(0);
        AtomicLong totalChars = new AtomicLong(0);
        StringBuilder fullResponse = new StringBuilder();

        return chatClient.prompt()
            .system(system)
            .user(fullPrompt)
            .stream()
            .chatResponse()
            // 注意：Reactor 的 map 不允许返回 null —— 映射函数一旦返回 null 就直接抛 NPE，
            // 后面的 filter(nonNull) 根本接不到。而 DeepSeek 这类 OpenAI 兼容协议一定会发
            // 没有 content 的 delta（首个带 role 的、结尾带 finish_reason 的），
            // 所以这里必须用 handle 主动「跳过」，不能靠 map 返回 null。
            .handle((ChatResponse response, SynchronousSink<Map<String, Object>> sink) -> {
                if (response.getResult() == null || response.getResult().getOutput() == null) {
                    return;
                }
                String content = response.getResult().getOutput().getContent();
                if (content == null || content.isEmpty()) {
                    return;
                }
                chunkCount.incrementAndGet();
                totalChars.addAndGet(content.length());
                fullResponse.append(content);
                // 日志：每个 chunk 的内容（长 chunk 截断）
                log.debug("LLM CHUNK #{} len={} content={}",
                    chunkCount.get(), content.length(),
                    content.length() > 200
                        ? content.substring(0, 200).replace("\n", "\\n") + "..."
                        : content.replace("\n", "\\n"));
                sink.next(Map.<String, Object>of("type", "content", "content", content));
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
