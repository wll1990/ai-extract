package com.aiextract.service;

import com.aiextract.config.PartnerCrypto;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.CompanyAiConfig;
import com.aiextract.repository.CompanyAiConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按企业解析 ChatClient。
 *
 * <p>兜底语义写死：未配置 / 停用且允许回退 / 解密失败且允许回退 → 平台 Key；
 * 运行中 401/402/429 <b>不自动回退</b>，让企业 Key 的问题暴露出来。</p>
 */
@Slf4j
@Component
public class ChatClientResolver {

    private final ChatClient platformClient;
    private final CompanyAiConfigRepository repository;
    private final PartnerCrypto crypto;
    private final CompanyChatClientFactory factory;
    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String platformModelName;

    public ChatClientResolver(@Qualifier("platformChatClient") ChatClient platformClient,
                              CompanyAiConfigRepository repository,
                              PartnerCrypto crypto,
                              CompanyChatClientFactory factory) {
        this.platformClient = platformClient;
        this.repository = repository;
        this.crypto = crypto;
        this.factory = factory;
    }

    public ChatClient current() {
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            return platformClient;
        }
        CompanyAiConfig cfg = repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId).orElse(null);
        if (cfg == null) {
            return platformClient;
        }
        if (!"enabled".equals(cfg.getStatus())) {
            if (cfg.isFallbackEnabled()) {
                return platformClient;
            }
            throw new BusinessException(409, "AI_KEY_DISABLED", "企业 AI Key 已停用，请联系管理员");
        }
        CacheEntry entry = cache.get(companyId);
        if (entry != null && entry.updatedAt().equals(cfg.getUpdatedAt())) {
            return entry.client();
        }
        String apiKey;
        try {
            apiKey = crypto.decrypt(cfg.getApiKeyCipher());
        } catch (Exception e) {
            log.error("企业 AI Key 解密失败 companyId={}", companyId, e);
            if (cfg.isFallbackEnabled()) {
                return platformClient;
            }
            throw new BusinessException(409, "AI_KEY_DECRYPT_FAILED", "企业 AI Key 解密失败，请联系管理员");
        }
        ChatClient client = factory.create(companyId, cfg.getBaseUrl(), apiKey, cfg.getModelName(),
                com.aiextract.config.ProviderCatalog.completionsPath(cfg.getProvider()),
                com.aiextract.config.ProviderCatalog.maxOutputTokens(cfg.getProvider()));
        cache.put(companyId, new CacheEntry(client, cfg.getUpdatedAt()));
        return client;
    }

    /** 当前请求实际使用的模型名（用于报告溯源）；回退平台或未配置时返回平台默认。 */
    public String currentModelName() {
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            return platformModelName;
        }
        return repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)
                .filter(cfg -> "enabled".equals(cfg.getStatus()))
                .map(CompanyAiConfig::getModelName)
                .orElse(platformModelName);
    }

    public void evict(UUID companyId) {
        cache.remove(companyId);
    }

    private record CacheEntry(ChatClient client, LocalDateTime updatedAt) {
    }
}
