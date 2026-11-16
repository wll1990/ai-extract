package com.aiextract.service;

import com.aiextract.common.AiErrorMapper;
import com.aiextract.config.PartnerCrypto;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.CompanyAiConfigRequest;
import com.aiextract.dto.CompanyAiConfigView;
import com.aiextract.dto.CompanyAiTestRequest;
import com.aiextract.dto.CompanyAiTestResult;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.CompanyAiConfig;
import com.aiextract.repository.CompanyAiConfigRepository;
import com.aiextract.repository.TokenUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 企业 AI Key 的读写、连通性测试与轮换。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanyAiConfigService {

    private final CompanyAiConfigRepository repository;
    private final PartnerCrypto crypto;
    private final ChatClientResolver resolver;
    private final SalesAuditService audit;
    private final TokenUsageLogRepository usageRepository;
    private final CompanyChatClientFactory factory;
    private final com.aiextract.config.OutboundUrlValidator outboundUrlValidator;

    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String platformModelName;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
    private String platformBaseUrl;

    public CompanyAiConfigView get(UUID companyId) {
        CompanyAiConfig cfg = repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId).orElse(null);
        TokenUsageLogRepository.CompanyUsageTotal usage =
                usageRepository.sumByCompanySince(companyId, LocalDate.now().withDayOfMonth(1));
        long input = usage == null || usage.getInputTokens() == null ? 0 : usage.getInputTokens();
        long output = usage == null || usage.getOutputTokens() == null ? 0 : usage.getOutputTokens();
        return new CompanyAiConfigView(
                cfg != null,
                cfg != null ? cfg.getProvider() : "deepseek",
                cfg == null ? platformModelName : cfg.getModelName(),
                cfg == null ? null : cfg.getBaseUrl(),
                cfg == null ? null : cfg.getKeyLast4(),
                cfg == null ? "disabled" : cfg.getStatus(),
                cfg == null || cfg.isFallbackEnabled(),
                true,
                input,
                output);
    }

    @Transactional
    public CompanyAiConfigView update(UUID companyId, CompanyAiConfigRequest req) {
        String provider = resolveProvider(req.provider(), companyId);
        String modelName = resolveModelName(req.modelName(), provider);
        validateEndpoint(provider, req.baseUrl());
        CompanyAiConfig cfg = repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)
                .orElseGet(() -> newConfig(companyId, provider));
        cfg.setProvider(provider);
        cfg.setModelName(modelName);
        cfg.setBaseUrl(req.baseUrl().trim());
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            String key = req.apiKey().trim();
            validateApiKey(key);
            cfg.setApiKeyCipher(crypto.encrypt(key));
            cfg.setKeyLast4(last4(key));
        }
        if (cfg.getApiKeyCipher() == null) {
            throw new BusinessException(400, "首次配置必须填写 API Key");
        }
        cfg.setFallbackEnabled(req.fallbackEnabled() == null || req.fallbackEnabled());
        cfg.setStatus("enabled");
        cfg.setUpdatedBy(TokenContext.get());
        cfg.setUpdatedAt(LocalDateTime.now());
        repository.save(cfg);
        resolver.evict(companyId);
        audit.record("ai.model.config.update", "company_ai_config", cfg.getId(),
                Map.of("provider", cfg.getProvider(),
                        "model", cfg.getModelName(),
                        "baseUrl", cfg.getBaseUrl(),
                        "keyLast4", cfg.getKeyLast4(),
                        "fallbackEnabled", cfg.isFallbackEnabled()));
        return get(companyId);
    }

    /** 连通性测试：不落库。管理员手动诊断，唯一允许在 HTTP 线程内打 LLM 的入口。 */
    public CompanyAiTestResult test(UUID companyId, CompanyAiTestRequest req) {
        String plain = resolvePlainKey(companyId, req == null ? null : req.apiKey());
        String url = req == null || req.baseUrl() == null || req.baseUrl().isBlank()
                ? resolveBaseUrl(companyId) : req.baseUrl().trim();
        String provider = resolveProvider(req == null ? null : req.provider(), companyId);
        validateEndpoint(provider, url);
        String model = req != null && req.modelName() != null && !req.modelName().isBlank()
                ? resolveModelName(req.modelName(), provider)
                : resolveModelName(companyId);
        long t0 = System.currentTimeMillis();
        try {
            ChatClient client = factory.create(companyId, url, plain, model,
                    com.aiextract.config.ProviderCatalog.completionsPath(provider),
                    com.aiextract.config.ProviderCatalog.maxOutputTokens(provider));
            client.prompt()
                    .user("ping")
                    .options(OpenAiChatOptions.builder().maxTokens(1).build())
                    .call()
                    .content();
            audit.record("ai.model.test", "company_ai_config", null,
                    Map.of("ok", true, "baseUrl", url));
            return new CompanyAiTestResult(true, System.currentTimeMillis() - t0, null, "连接成功");
        } catch (Exception e) {
            log.warn("企业 AI Key 连通性测试失败 companyId={} baseUrl={}", companyId, url, e);
            AiErrorMapper.AiError aiError = AiErrorMapper.map(e);
            audit.record("ai.model.test", "company_ai_config", null,
                    Map.of("ok", false, "baseUrl", url, "errorCode", aiError.code()));
            return new CompanyAiTestResult(false, System.currentTimeMillis() - t0,
                    aiError.code(), aiError.message());
        }
    }

    @Transactional
    public void rotate(UUID companyId, String newKey) {
        validateApiKey(newKey);
        CompanyAiConfig cfg = require(companyId);
        String previousKeyLast4 = cfg.getKeyLast4();
        cfg.setApiKeyCipher(crypto.encrypt(newKey.trim()));
        cfg.setKeyLast4(last4(newKey.trim()));
        cfg.setUpdatedBy(TokenContext.get());
        cfg.setUpdatedAt(LocalDateTime.now());
        repository.save(cfg);
        resolver.evict(companyId);
        // 旧 Key 不再落库：审计留末 4 位与废弃标记即可
        audit.record("ai.model.rotate", "company_ai_config", cfg.getId(),
                Map.of("keyLast4", cfg.getKeyLast4(),
                        "previousKeyLast4", previousKeyLast4 == null ? "" : previousKeyLast4,
                        "previousKeyRetained", false));
    }

    @Transactional
    public void disable(UUID companyId) {
        CompanyAiConfig cfg = require(companyId);
        cfg.setStatus("disabled");
        cfg.setUpdatedBy(TokenContext.get());
        cfg.setUpdatedAt(LocalDateTime.now());
        repository.save(cfg);
        resolver.evict(companyId);
        audit.record("ai.model.disable", "company_ai_config", cfg.getId(),
                Map.of("fallbackEnabled", cfg.isFallbackEnabled()));
    }

    private CompanyAiConfig require(UUID companyId) {
        return repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)
                .orElseThrow(() -> new BusinessException(404, "企业尚未配置 AI Key"));
    }

    private CompanyAiConfig newConfig(UUID companyId, String provider) {
        LocalDateTime now = LocalDateTime.now();
        com.aiextract.config.ProviderCatalog.Provider catalog =
                com.aiextract.config.ProviderCatalog.get(provider);
        return CompanyAiConfig.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .provider(provider)
                .baseUrl(catalog != null && catalog.defaultBaseUrl() != null
                        ? catalog.defaultBaseUrl() : platformBaseUrl)
                .modelName(catalog != null && catalog.defaultModel() != null
                        ? catalog.defaultModel() : platformModelName)
                .status("disabled")
                .fallbackEnabled(true)
                .createdBy(TokenContext.get())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private String resolvePlainKey(UUID companyId, String inputKey) {
        if (inputKey != null && !inputKey.isBlank()) {
            return inputKey.trim();
        }
        CompanyAiConfig cfg = repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)
                .orElseThrow(() -> new BusinessException(400, "未填写 API Key，企业也尚未配置"));
        return crypto.decrypt(cfg.getApiKeyCipher());
    }

    private String resolveBaseUrl(UUID companyId) {
        return repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)
                .map(CompanyAiConfig::getBaseUrl)
                .orElse(platformBaseUrl);
    }

    private String resolveModelName(UUID companyId) {
        return repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)
                .map(CompanyAiConfig::getModelName)
                .orElse(platformModelName);
    }

    /** 请求显式指定厂商时校验白名单；否则沿用已存配置，默认 deepseek */
    private String resolveProvider(String requested, UUID companyId) {
        if (requested != null && !requested.isBlank()) {
            String id = requested.trim().toLowerCase();
            if (!com.aiextract.config.ProviderCatalog.isSupported(id)) {
                throw new BusinessException(400, "不支持的模型厂商：" + requested);
            }
            return id;
        }
        return repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)
                .map(CompanyAiConfig::getProvider)
                .filter(com.aiextract.config.ProviderCatalog::isSupported)
                .orElse("deepseek");
    }

    /** 模型名：请求优先，其次厂商默认，最后平台默认 */
    private String resolveModelName(String requested, String provider) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        com.aiextract.config.ProviderCatalog.Provider catalog =
                com.aiextract.config.ProviderCatalog.get(provider);
        return catalog != null && catalog.defaultModel() != null
                ? catalog.defaultModel() : platformModelName;
    }

    /**
     * 端点校验：官方域名白名单 + SSRF 防护。
     * 本地部署厂商（custom / ollama / vllm）允许私网地址，但必须命中
     * app.ai.local-model-hosts 白名单，否则按内网地址拒绝。
     */
    private void validateEndpoint(String provider, String url) {
        com.aiextract.config.ProviderCatalog.Provider catalog =
                com.aiextract.config.ProviderCatalog.get(provider);
        boolean customBaseUrlAllowed = catalog != null && catalog.customBaseUrlAllowed();
        outboundUrlValidator.validateModelEndpoint(url, customBaseUrlAllowed);
        if (!com.aiextract.config.ProviderCatalog.matchesOfficialHost(provider, url)) {
            throw new BusinessException(400,
                    "该厂商的 Base URL 必须使用官方域名；如需自定义端点请选择 custom");
        }
    }

    private void validateApiKey(String key) {
        if (key == null || key.trim().length() < 8 || key.trim().length() > 300) {
            throw new BusinessException(400, "API Key 长度需在 8–300 之间");
        }
    }

    private String last4(String key) {
        String k = key.trim();
        return k.length() <= 4 ? k : k.substring(k.length() - 4);
    }

}
