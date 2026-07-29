package com.aiextract.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 发 LLM 前 token 预检 — 估算总 token，超窗口 80% 自动裁剪最早消息。
 *
 * <p>中文约 1 字 ≈ 0.5 token，留 20% 安全余量。Chat 和 Interview 统一使用。
 *
 * @author AI Extract Team
 * @since 2026-07-30
 */
@Slf4j
@Component
public class ContextWindowGuard {

    @Value("${app.rag.context-max-tokens:8192}")
    private int maxContextTokens;

    private static final double SAFETY_RATIO = 0.80;
    private static final int CHARS_PER_TOKEN = 2; // 中文近似

    /**
     * 检查并裁剪消息列表，确保不超出上下文窗口。
     *
     * @param messages          消息列表（不含 system prompt）
     * @param systemPromptChars system prompt 字符数
     * @return 裁剪后的消息列表（可能与原列表相同）
     */
    public List<Map<String, String>> trimIfNeeded(
            List<Map<String, String>> messages, int systemPromptChars) {
        int totalChars = systemPromptChars;
        for (var msg : messages) {
            String content = msg.get("content");
            if (content != null) {
                totalChars += content.length();
            }
        }
        int estimatedTokens = totalChars / CHARS_PER_TOKEN;
        int maxAllowed = (int) (maxContextTokens * SAFETY_RATIO);

        if (estimatedTokens <= maxAllowed) {
            return messages; // 安全，不改
        }

        // 超出 → 从最早的消息开始裁剪
        log.warn("上下文超限 estimatedTokens={} maxAllowed={} 开始裁剪最早消息",
                estimatedTokens, maxAllowed);
        int targetChars = maxAllowed * CHARS_PER_TOKEN;
        int keepFrom = 0;
        int runningChars = systemPromptChars;
        for (int i = messages.size() - 1; i >= 0; i--) {
            String content = messages.get(i).get("content");
            runningChars += content != null ? content.length() : 0;
            if (runningChars > targetChars) {
                keepFrom = i + 1;
                break;
            }
        }

        List<Map<String, String>> trimmed = new ArrayList<>(
                messages.subList(keepFrom, messages.size()));
        log.info("上下文裁剪完成 {}条→{}条 (keepFrom={})",
                messages.size(), trimmed.size(), keepFrom);
        return trimmed;
    }

    /** 估算总 token 数（用于日志和监控） */
    public int estimateTokens(List<Map<String, String>> messages, int systemPromptChars) {
        int totalChars = systemPromptChars;
        for (var msg : messages) {
            String content = msg.get("content");
            if (content != null) {
                totalChars += content.length();
            }
        }
        return totalChars / CHARS_PER_TOKEN;
    }
}
