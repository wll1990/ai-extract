package com.aiextract;

import com.aiextract.config.ProviderCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 厂商目录：路径拼接与官方域名约束。
 */
class ProviderCatalogTest {

    @Test
    void deepseekUsesV1ChatCompletions() {
        assertEquals("/v1/chat/completions", ProviderCatalog.completionsPath("deepseek"));
        assertEquals("https://api.deepseek.com",
                ProviderCatalog.get("deepseek").defaultBaseUrl());
    }

    @Test
    void qwenCompatibleBaseUrlDoesNotContainV1() {
        // DashScope 兼容模式：base 带 /v1 会拼成 .../compatible-mode/v1/v1/chat/completions
        String baseUrl = ProviderCatalog.get("qwen").defaultBaseUrl();
        assertFalse(baseUrl.endsWith("/v1"));
        assertTrue(baseUrl.endsWith("/compatible-mode"));
    }

    @Test
    void nonCustomProviderRejectsForeignHost() {
        assertTrue(ProviderCatalog.matchesOfficialHost("deepseek", "https://api.deepseek.com"));
        assertTrue(ProviderCatalog.matchesOfficialHost("deepseek", "https://api.deepseek.com/v1"));
        assertFalse(ProviderCatalog.matchesOfficialHost("deepseek", "https://example.com"));
        assertFalse(ProviderCatalog.matchesOfficialHost("qwen",
                "https://dashscope.aliyuncs.com.evil.com"));
    }

    @Test
    void customAllowsAnyHost() {
        assertTrue(ProviderCatalog.matchesOfficialHost("custom", "https://llm.internal.example.com"));
    }

    @Test
    void domesticVendorsUseTheirOwnPaths() {
        // 智谱 /api/paas/v4、火山 /api/v3、千帆 /v2 都不是 Spring AI 默认拼接能命中的路径
        assertEquals("/chat/completions", ProviderCatalog.completionsPath("zhipu"));
        assertEquals("https://open.bigmodel.cn/api/paas/v4",
                ProviderCatalog.get("zhipu").defaultBaseUrl());
        assertEquals("/chat/completions", ProviderCatalog.completionsPath("doubao"));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3",
                ProviderCatalog.get("doubao").defaultBaseUrl());
        assertEquals("/chat/completions", ProviderCatalog.completionsPath("qianfan"));
        assertEquals("/v1/chat/completions", ProviderCatalog.completionsPath("hunyuan"));
    }

    @Test
    void aggregatorsAndLocalDeployments() {
        assertEquals("/v1/chat/completions", ProviderCatalog.completionsPath("openrouter"));
        assertEquals("https://openrouter.ai/api",
                ProviderCatalog.get("openrouter").defaultBaseUrl());
        assertTrue(ProviderCatalog.get("ollama").customBaseUrlAllowed());
        assertTrue(ProviderCatalog.get("vllm").customBaseUrlAllowed());
        // 本地部署允许换主机，但仍由 OutboundUrlValidator 白名单兜底
        assertTrue(ProviderCatalog.matchesOfficialHost("ollama", "http://llm.corp.local:11434"));
        assertFalse(ProviderCatalog.get("deepseek").customBaseUrlAllowed());
    }

    @Test
    void outputLimitsComeFromProvider() {
        assertEquals(8192, ProviderCatalog.maxOutputTokens("deepseek"));
        assertEquals(16384, ProviderCatalog.maxOutputTokens("openai"));
    }
}
