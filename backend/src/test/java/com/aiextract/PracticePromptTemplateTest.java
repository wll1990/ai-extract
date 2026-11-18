package com.aiextract;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对练模板占位符守卫。
 *
 * <p>PromptLoader 优先命中领域模板，领域文件漏加占位符时新字段会静默失效，
 * 因此三个客户模板都必须包含 {@code {kc_customer_context}}。</p>
 */
class PracticePromptTemplateTest {

    @Test
    void customerTemplates_containKnowledgeCasePlaceholder() throws IOException {
        List<String> templates = List.of(
                "prompts/skill_practice_customer.md",
                "prompts/domain/sales.b2b_enterprise/skill_practice_customer.md",
                "prompts/domain/finance.secondary_market/skill_practice_customer.md");
        for (String path : templates) {
            String content = read(path);
            assertTrue(content.contains("{kc_customer_context}"),
                    path + " 缺少 {kc_customer_context}，领域模板会覆盖基础模板导致注入失效");
        }
    }

    @Test
    void evaluateRoundTemplate_containsKnowledgeCasePlaceholder() throws IOException {
        String content = read("prompts/practice_evaluate_round.md");
        assertTrue(content.contains("{kc_context}"));
    }

    private String read(String path) throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            assertTrue(in != null, "模板不存在: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
