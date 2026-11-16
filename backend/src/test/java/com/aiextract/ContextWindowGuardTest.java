package com.aiextract;

import com.aiextract.service.ContextWindowGuard;
import com.aiextract.service.TokenEstimationCalibrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文裁剪不变式：超预算时至少保留最近 N 条，永不返回空列表。
 */
class ContextWindowGuardTest {

    private ContextWindowGuard guard;

    @BeforeEach
    void setUp() {
        TokenEstimationCalibrator.reset();
        guard = new ContextWindowGuard();
        ReflectionTestUtils.setField(guard, "maxContextTokens", 1000);
        ReflectionTestUtils.setField(guard, "outputReserveTokens", 200);
        ReflectionTestUtils.setField(guard, "safetyRatio", 0.9);
        ReflectionTestUtils.setField(guard, "minKeepMessages", 4);
        ReflectionTestUtils.setField(guard, "defaultCharsPerToken", 1.5);
    }

    @AfterEach
    void tearDown() {
        TokenEstimationCalibrator.reset();
    }

    @Test
    void keepsAllMessagesWhenUnderBudget() {
        List<Map<String, String>> messages = history(2, 10);
        assertSame(messages, guard.trimIfNeeded(messages, 10));
    }

    @Test
    void keepsAtLeastMinMessagesWhenSystemPromptAloneExceedsBudget() {
        // 预算 720 token，system prompt 本身就 2000 token
        List<Map<String, String>> messages = history(10, 20);
        List<Map<String, String>> trimmed = guard.trimIfNeeded(messages, 3000);

        assertFalse(trimmed.isEmpty(), "超预算时不能把消息裁空");
        assertEquals(4, trimmed.size(), "至少保留 min-keep-messages 条");
        assertEquals(messages.get(messages.size() - 1), trimmed.get(trimmed.size() - 1),
                "必须保留当前问题（最后一条）");
    }

    @Test
    void keepsRecentMessagesWhenOverBudget() {
        List<Map<String, String>> messages = history(20, 100);
        List<Map<String, String>> trimmed = guard.trimIfNeeded(messages, 200);

        assertFalse(trimmed.isEmpty());
        assertEquals(messages.get(messages.size() - 1), trimmed.get(trimmed.size() - 1));
        assertEquals(messages.get(messages.size() - 2), trimmed.get(trimmed.size() - 2));
    }

    @Test
    void calibrationRaisesEstimatedTokensForChineseText() {
        int before = guard.estimateTokens(history(1, 1000), 0);
        // 真实 usage 表明每 token 只有 1 个字符 → 校准系数变大，估算 token 变多
        TokenEstimationCalibrator.observe("deepseek-chat", 1000, 1000);
        int after = guard.estimateTokens(history(1, 1000), 0);
        assertTrue(after >= before);
    }

    private List<Map<String, String>> history(int count, int charsPerMessage) {
        List<Map<String, String>> messages = new ArrayList<>();
        String content = "经".repeat(charsPerMessage);
        for (int i = 0; i < count; i++) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("role", i % 2 == 0 ? "user" : "assistant");
            m.put("content", content);
            messages.add(m);
        }
        return messages;
    }
}
