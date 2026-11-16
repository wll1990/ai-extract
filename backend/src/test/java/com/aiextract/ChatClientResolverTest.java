package com.aiextract;

import com.aiextract.config.PartnerCrypto;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.CompanyAiConfig;
import com.aiextract.repository.CompanyAiConfigRepository;
import com.aiextract.service.ChatClientResolver;
import com.aiextract.service.CompanyChatClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatClientResolverTest {

    private ChatClient platform;
    private CompanyAiConfigRepository repository;
    private PartnerCrypto crypto;
    private CompanyChatClientFactory factory;
    private ChatClientResolver resolver;

    @BeforeEach
    void setUp() {
        platform = mock(ChatClient.class);
        repository = mock(CompanyAiConfigRepository.class);
        crypto = mock(PartnerCrypto.class);
        factory = mock(CompanyChatClientFactory.class);
        resolver = new ChatClientResolver(platform, repository, crypto, factory);
        TokenContext.clear();
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void noCompanyContext_usesPlatformWithoutDbHit() {
        assertSame(platform, resolver.current());
        verifyNoInteractions(repository, factory);
    }

    @Test
    void noConfig_usesPlatform() {
        UUID companyId = UUID.randomUUID();
        TokenContext.set(null, companyId);
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)).thenReturn(Optional.empty());

        assertSame(platform, resolver.current());
        verifyNoInteractions(factory);
    }

    @Test
    void enabledConfig_usesCompanyClientAndCaches() {
        UUID companyId = UUID.randomUUID();
        TokenContext.set(null, companyId);
        CompanyAiConfig cfg = config(companyId, "enabled", true);
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)).thenReturn(Optional.of(cfg));
        when(crypto.decrypt("cipher")).thenReturn("sk-12345678");
        ChatClient companyClient = mock(ChatClient.class);
        when(factory.create(companyId, "https://api.deepseek.com", "sk-12345678", "deepseek-chat",
                "/v1/chat/completions", 8192))
                .thenReturn(companyClient);

        assertSame(companyClient, resolver.current());
        assertSame(companyClient, resolver.current());
        verify(factory, times(1)).create(any(), anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void disabledWithoutFallback_throwsStableSubCode() {
        UUID companyId = UUID.randomUUID();
        TokenContext.set(null, companyId);
        CompanyAiConfig cfg = config(companyId, "disabled", false);
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)).thenReturn(Optional.of(cfg));

        BusinessException e = assertThrows(BusinessException.class, resolver::current);
        assertEquals("AI_KEY_DISABLED", e.getSubCode());
        verifyNoInteractions(factory);
    }

    @Test
    void disabledWithFallback_usesPlatform() {
        UUID companyId = UUID.randomUUID();
        TokenContext.set(null, companyId);
        CompanyAiConfig cfg = config(companyId, "disabled", true);
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)).thenReturn(Optional.of(cfg));

        assertSame(platform, resolver.current());
        verifyNoInteractions(factory);
    }

    @Test
    void decryptFailureWithFallback_usesPlatform() {
        UUID companyId = UUID.randomUUID();
        TokenContext.set(null, companyId);
        CompanyAiConfig cfg = config(companyId, "enabled", true);
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)).thenReturn(Optional.of(cfg));
        when(crypto.decrypt(anyString())).thenThrow(new RuntimeException("bad cipher"));

        assertSame(platform, resolver.current());
        verifyNoInteractions(factory);
    }

    @Test
    void decryptFailureWithoutFallback_throwsStableSubCode() {
        UUID companyId = UUID.randomUUID();
        TokenContext.set(null, companyId);
        CompanyAiConfig cfg = config(companyId, "enabled", false);
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)).thenReturn(Optional.of(cfg));
        when(crypto.decrypt(anyString())).thenThrow(new RuntimeException("bad cipher"));

        BusinessException e = assertThrows(BusinessException.class, resolver::current);
        assertEquals("AI_KEY_DECRYPT_FAILED", e.getSubCode());
    }

    @Test
    void evict_forcesRecreate() {
        UUID companyId = UUID.randomUUID();
        TokenContext.set(null, companyId);
        CompanyAiConfig cfg = config(companyId, "enabled", true);
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)).thenReturn(Optional.of(cfg));
        when(crypto.decrypt("cipher")).thenReturn("sk-12345678");
        when(factory.create(any(), anyString(), anyString(), anyString()))
                .thenReturn(mock(ChatClient.class));

        resolver.current();
        resolver.evict(companyId);
        resolver.current();

        verify(factory, times(2)).create(any(), anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    private CompanyAiConfig config(UUID companyId, String status, boolean fallback) {
        LocalDateTime now = LocalDateTime.now();
        return CompanyAiConfig.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .provider("deepseek")
                .baseUrl("https://api.deepseek.com")
                .apiKeyCipher("cipher")
                .keyLast4("5678")
                .modelName("deepseek-chat")
                .status(status)
                .fallbackEnabled(fallback)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
