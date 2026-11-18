package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.service.skill.AgentSkillToolCallbacks;
import com.aiextract.service.skill.SkillBudget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    private final AgentSkillToolCallbacks agentSkillToolCallbacks;

    public ChatStreamAdapter(ChatClient chatClient, AgentSkillToolCallbacks agentSkillToolCallbacks) {
        this.chatClient = chatClient;
        this.agentSkillToolCallbacks = agentSkillToolCallbacks;
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
        // 供应商约束：只允许一条 system，且放在最前；空 assistant 消息会被拒绝。
        StringBuilder systemContent = new StringBuilder();
        List<Message> dialogMessages = new ArrayList<>(messages.size());
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
            switch (role == null ? "" : role) {
                case "system" -> systemContent.append(content).append("\n\n");
                case "assistant" -> {
                    if (!content.isBlank()) {
                        dialogMessages.add(new AssistantMessage(content));
                    }
                }
                default -> dialogMessages.add(new UserMessage(content));
            }
        }

        List<Message> chatMessages = new ArrayList<>(dialogMessages.size() + 1);
        if (!systemContent.isEmpty()) {
            chatMessages.add(new SystemMessage(systemContent.toString().trim()));
        }
        chatMessages.addAll(dialogMessages);
        if (chatMessages.isEmpty()) {
            chatMessages.add(new UserMessage(""));
        }
        log.debug("LLM REQ messages={} systemChars={} dialogChars={}",
            chatMessages.size(), systemContent.length(),
            dialogMessages.stream().mapToInt(m -> m.getText() == null ? 0 : m.getText().length()).sum());

        AtomicInteger chunkCount = new AtomicInteger(0);
        AtomicLong totalChars = new AtomicLong(0);
        StringBuilder fullResponse = new StringBuilder();

        Map<String, Object> toolContext = buildToolContext(context);
        var chatRequest = chatClient.prompt()
            .messages(chatMessages)
            .toolContext(toolContext);
        // 技能工具按请求注册：未标记的链路（评测/访谈/训练等）既看不到工具也调不到
        if (Boolean.TRUE.equals(toolContext.get("agentSkills"))) {
            chatRequest.toolCallbacks(agentSkillToolCallbacks.getToolCallbacks());
        }
        return chatRequest
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
                String content = response.getResult().getOutput().getText();
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

    /**
     * 工具上下文：先复制调用方 context（**不修改传入的不可变 Map**），
     * 再用请求线程的 TokenContext 权威覆盖身份键，防止调用方伪造租户。
     */
    private static Map<String, Object> buildToolContext(Map<String, Object> callerContext) {
        Map<String, Object> context = new HashMap<>();
        if (callerContext != null) {
            context.putAll(callerContext);
        }
        context.remove("userId");
        context.remove("companyId");
        UUID userId = TokenContext.get();
        UUID companyId = TokenContext.getCompanyId();
        if (userId != null) {
            context.put("userId", userId.toString());
        }
        if (companyId != null) {
            context.put("companyId", companyId.toString());
        }
        if (Boolean.TRUE.equals(context.get("agentSkills"))) {
            context.put("skillBudget", new SkillBudget());
        }
        return context;
    }
}
