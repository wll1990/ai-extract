package com.aiextract.config;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话模型厂商目录 —— 只收录 OpenAI 兼容协议。
 *
 * <p>每个厂商声明默认 Base URL、默认模型、completions 路径、输出上限与上下文窗口。
 * Base URL 统一不含 {@code /v1}，由 {@link #completionsPath(String)} 拼完整路径，
 * 这样各厂商（含 DashScope 兼容模式、智谱 v4、火山方舟 v3）都能命中正确端点。</p>
 *
 * <p>{@code customBaseUrlAllowed=true} 的厂商（自定义端点、本地 Ollama/vLLM）允许
 * 管理员改 Base URL；本地部署还要命中 {@code app.ai.local-model-hosts} 白名单，
 * 否则 {@link OutboundUrlValidator} 会按 SSRF 规则拒绝。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
public final class ProviderCatalog {

    public record Provider(
            String id,
            String label,
            String defaultBaseUrl,
            String defaultModel,
            String completionsPath,
            int maxOutputTokens,
            int contextWindow,
            boolean customBaseUrlAllowed
    ) {
    }

    private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();

    static {
        register(new Provider("deepseek", "DeepSeek",
                "https://api.deepseek.com", "deepseek-chat",
                "/v1/chat/completions", 8192, 65536, false));
        register(new Provider("openai", "OpenAI",
                "https://api.openai.com", "gpt-4o-mini",
                "/v1/chat/completions", 16384, 128000, false));
        // DashScope 兼容模式：Base URL 不能带 /v1，否则会拼成 /compatible-mode/v1/v1/chat/completions
        register(new Provider("qwen", "通义千问（兼容模式）",
                "https://dashscope.aliyuncs.com/compatible-mode", "qwen-plus",
                "/v1/chat/completions", 8192, 131072, false));
        register(new Provider("moonshot", "Moonshot",
                "https://api.moonshot.cn", "moonshot-v1-8k",
                "/v1/chat/completions", 8192, 8192, false));

        // ── 国产主流：同为 OpenAI 协议，仅端点路径不同 ──
        // 火山方舟模型 ID 也可填推理接入点 ep-xxxxxxxx
        register(new Provider("doubao", "豆包 / 火山方舟",
                "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k",
                "/chat/completions", 8192, 32768, false));
        register(new Provider("zhipu", "智谱 GLM",
                "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus",
                "/chat/completions", 4096, 131072, false));
        register(new Provider("hunyuan", "腾讯混元",
                "https://api.hunyuan.cloud.tencent.com", "hunyuan-turbos-latest",
                "/v1/chat/completions", 8192, 131072, false));
        register(new Provider("qianfan", "百度千帆",
                "https://qianfan.baidubce.com/v2", "ernie-4.5-turbo-128k",
                "/chat/completions", 8192, 131072, false));

        // ── 聚合网关：一个 Key 通多家模型，适合横向对比 ──
        register(new Provider("siliconflow", "SiliconFlow",
                "https://api.siliconflow.cn", "Qwen/Qwen2.5-72B-Instruct",
                "/v1/chat/completions", 8192, 32768, false));
        // OpenRouter 官方 Base 是 /api/v1，这里拆成 /api + /v1/chat/completions
        register(new Provider("openrouter", "OpenRouter",
                "https://openrouter.ai/api", "deepseek/deepseek-chat",
                "/v1/chat/completions", 8192, 65536, false));
        register(new Provider("groq", "Groq",
                "https://api.groq.com/openai", "llama-3.3-70b-versatile",
                "/v1/chat/completions", 8192, 131072, false));

        // ── 本地/私有部署：允许改 Base URL，但必须命中 app.ai.local-model-hosts 白名单 ──
        register(new Provider("ollama", "本地 Ollama",
                "http://localhost:11434", "qwen2.5:14b",
                "/v1/chat/completions", 4096, 32768, true));
        register(new Provider("vllm", "本地 vLLM",
                "http://localhost:8000", "Qwen/Qwen2.5-32B-Instruct",
                "/v1/chat/completions", 8192, 32768, true));

        register(new Provider("custom", "自定义（OpenAI 兼容）",
                null, null,
                "/v1/chat/completions", 8192, 32768, true));
    }

    private ProviderCatalog() {
    }

    private static void register(Provider provider) {
        PROVIDERS.put(provider.id(), provider);
    }

    public static Provider get(String providerId) {
        return providerId == null ? null : PROVIDERS.get(providerId.trim().toLowerCase());
    }

    public static Provider require(String providerId) {
        Provider provider = get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("不支持的模型厂商：" + providerId);
        }
        return provider;
    }

    public static boolean isSupported(String providerId) {
        return get(providerId) != null;
    }

    public static Collection<Provider> all() {
        return PROVIDERS.values();
    }

    public static String completionsPath(String providerId) {
        Provider provider = get(providerId);
        return provider == null ? "/v1/chat/completions" : provider.completionsPath();
    }

    public static int maxOutputTokens(String providerId) {
        Provider provider = get(providerId);
        return provider == null ? 8192 : provider.maxOutputTokens();
    }

    public static int contextWindow(String providerId) {
        Provider provider = get(providerId);
        return provider == null ? 32768 : provider.contextWindow();
    }

    /**
     * 不允许自定义端点的厂商必须使用官方域名，防止企业管理员把 Base URL
     * 指向自己的域名窃取 Key。允许官方域名的子域（如区域端点）。
     */
    public static boolean matchesOfficialHost(String providerId, String baseUrl) {
        Provider provider = get(providerId);
        if (provider == null || provider.defaultBaseUrl() == null || provider.customBaseUrlAllowed()) {
            return true;
        }
        try {
            String expected = URI.create(provider.defaultBaseUrl()).getHost();
            String actual = URI.create(baseUrl).getHost();
            if (expected == null || actual == null) {
                return false;
            }
            return actual.equalsIgnoreCase(expected)
                    || actual.toLowerCase().endsWith("." + expected.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }
}
