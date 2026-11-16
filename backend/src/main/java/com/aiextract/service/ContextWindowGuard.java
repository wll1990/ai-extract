package com.aiextract.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 发 LLM 前 token 预检 — 按模型上下文窗口估算总 token，超预算时裁剪最早消息。
 *
 * <p>预算 = (contextMaxTokens - outputReserveTokens) × safetyRatio，
 * 为输出和估算误差留出余量。字符/token 系数优先使用
 * {@link TokenEstimationCalibrator} 用真实 Usage 校准出的值，未校准时用配置值。</p>
 *
 * <p>不变式：消息列表非空时，裁剪结果永远非空；至少保留最近
 * {@code app.rag.min-keep-messages} 条（系统提示词超预算时也成立）。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-30
 */
@Slf4j
@Component
public class ContextWindowGuard {

    /** 兜底窗口；多厂商接入后由 ProviderCatalog 按模型覆盖 */
    @Value("${app.rag.context-max-tokens:65536}")
    private int maxContextTokens;

    /** 为输出预留的 token（DeepSeek 输出上限 8192） */
    @Value("${app.rag.output-reserve-tokens:8192}")
    private int outputReserveTokens;

    @Value("${app.rag.safety-ratio:0.9}")
    private double safetyRatio;

    @Value("${app.rag.min-keep-messages:6}")
    private int minKeepMessages;

    /** 初始字符/token；运行期用校准值覆盖 */
    @Value("${app.rag.chars-per-token:1.5}")
    private double defaultCharsPerToken;

    /**
     * 检查并裁剪消息列表，确保不超出上下文窗口。
     *
     * @param messages          消息列表（不含 system prompt）
     * @param systemPromptChars system prompt 字符数
     * @return 裁剪后的消息列表（可能与原列表相同）
     */
    public List<Map<String, String>> trimIfNeeded(
            List<Map<String, String>> messages, int systemPromptChars) {
        if (messages == null || messages.isEmpty()) {
            return messages == null ? List.of() : messages;
        }

        double charsPerToken = effectiveCharsPerToken();
        int maxAllowed = maxAllowedTokens();
        int estimatedTokens = estimateTokens(messages, systemPromptChars, charsPerToken);

        if (estimatedTokens <= maxAllowed) {
            return messages; // 安全，不改
        }

        int targetChars = (int) (maxAllowed * charsPerToken);
        if (systemPromptChars > targetChars) {
            log.warn("系统提示词单独超预算 systemPromptChars={} targetChars={} maxAllowedTokens={} modelCharsPerToken={}",
                    systemPromptChars, targetChars, maxAllowed, charsPerToken);
        }

        // 超出 → 从最早的消息开始裁剪
        log.warn("上下文超限 estimatedTokens={} maxAllowedTokens={} 开始裁剪最早消息",
                estimatedTokens, maxAllowed);
        int keepFrom = messages.size();
        int runningChars = systemPromptChars;
        for (int i = messages.size() - 1; i >= 0; i--) {
            String content = messages.get(i).get("content");
            runningChars += content != null ? content.length() : 0;
            if (runningChars > targetChars) {
                keepFrom = i + 1;
                break;
            }
        }

        // 保底：至少保留最近 minKeepMessages 条；消息非空时绝不返回空列表
        int minKeep = Math.min(Math.max(minKeepMessages, 1), messages.size());
        keepFrom = Math.min(keepFrom, messages.size() - minKeep);
        keepFrom = Math.max(0, Math.min(keepFrom, messages.size()));

        List<Map<String, String>> trimmed = new ArrayList<>(
                messages.subList(keepFrom, messages.size()));
        log.info("上下文裁剪完成 {}条→{}条 (keepFrom={}, systemPromptChars={})",
                messages.size(), trimmed.size(), keepFrom, systemPromptChars);
        return trimmed;
    }

    /** 估算总 token 数（用于日志和监控） */
    public int estimateTokens(List<Map<String, String>> messages, int systemPromptChars) {
        return estimateTokens(messages, systemPromptChars, effectiveCharsPerToken());
    }

    /** 当前可用的输入预算（token） */
    public int maxAllowedTokens() {
        long budget = Math.round((maxContextTokens - (long) outputReserveTokens) * safetyRatio);
        return (int) Math.max(1, budget);
    }

    private int estimateTokens(List<Map<String, String>> messages, int systemPromptChars,
                               double charsPerToken) {
        int totalChars = systemPromptChars;
        for (var msg : messages) {
            String content = msg.get("content");
            if (content != null) {
                totalChars += content.length();
            }
        }
        return (int) Math.ceil(totalChars / charsPerToken);
    }

    private double effectiveCharsPerToken() {
        double calibrated = TokenEstimationCalibrator.charsPerToken();
        return calibrated > 0 ? calibrated : defaultCharsPerToken;
    }
}
