package com.aiextract;

import com.aiextract.config.PartnerCrypto;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.CompanyAiConfigRequest;
import com.aiextract.dto.CompanyAiConfigView;
import com.aiextract.dto.CompanyAiTestRequest;
import com.aiextract.dto.CompanyAiTestResult;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.CompanyAiConfig;
import com.aiextract.repository.CompanyAiConfigHistoryRepository;
import com.aiextract.repository.CompanyAiConfigRepository;
import com.aiextract.repository.TokenUsageLogRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.ChatClientResolver;
import com.aiextract.service.CompanyAiConfigService;
import com.aiextract.service.CompanyChatClientFactory;
import com.aiextract.service.SalesAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class CompanyAiConfigServiceTest {

    private CompanyAiConfigRepository repository;
    private CompanyAiConfigHistoryRepository historyRepository;
    private UserRepository userRepository;
    private PartnerCrypto crypto;
    private ChatClientResolver resolver;
    private SalesAuditService audit;
    private TokenUsageLogRepository usageRepository;
    private CompanyChatClientFactory factory;
    private com.aiextract.config.OutboundUrlValidator outboundUrlValidator;
    private CompanyAiConfigService service;

    @BeforeEach
    void setUp() {
        repository = mock(CompanyAiConfigRepository.class);
        historyRepository = mock(CompanyAiConfigHistoryRepository.class);
        userRepository = mock(UserRepository.class);
        crypto = mock(PartnerCrypto.class);
        resolver = mock(ChatClientResolver.class);
        audit = mock(SalesAuditService.class);
        usageRepository = mock(TokenUsageLogRepository.class);
        factory = mock(CompanyChatClientFactory.class);
        outboundUrlValidator = mock(com.aiextract.config.OutboundUrlValidator.class);
        service = new CompanyAiConfigService(repository, historyRepository, userRepository, crypto, resolver,
                audit, usageRepository, factory, outboundUrlValidator);
        ReflectionTestUtils.setField(service, "platformModelName", "deepseek-chat");
        ReflectionTestUtils.setField(service, "platformBaseUrl", "https://api.deepseek.com");
        TokenContext.clear();
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void getUnconfigured_returnsPlatformDefaults() {
        UUID companyId = UUID.randomUUID();
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)).thenReturn(Optional.empty());
        TokenUsageLogRepository.CompanyUsageTotal usage = mock(TokenUsageLogRepository.CompanyUsageTotal.class);
        when(usage.getInputTokens()).thenReturn(0L);
        when(usage.getOutputTokens()).thenReturn(0L);
        when(usageRepository.sumByCompanySince(any(), any())).thenReturn(usage);

        CompanyAiConfigView view = service.get(companyId);

        assertFalse(view.configured());
        assertEquals("deepseek-chat", view.model());
        assertEquals("disabled", view.status());
        assertTrue(view.fallbackEnabled());
        assertEquals(0L, view.inputTokensThisMonth());
        assertEquals("platform_key", view.effectiveMode());
        assertEquals("deepseek-chat", view.effectiveModel());
        assertEquals("https://api.deepseek.com", view.effectiveBaseUrl());
    }

    @Test
    void disabledWithoutFallback_reportsUnavailable() {
        UUID companyId = UUID.randomUUID();
        CompanyAiConfig cfg = CompanyAiConfig.builder()
                .id(UUID.randomUUID()).companyId(companyId).provider("deepseek")
                .baseUrl("https://api.deepseek.com").apiKeyCipher("cipher").keyLast4("5678")
                .modelName("deepseek-chat").status("disabled").fallbackEnabled(false)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)).thenReturn(Optional.of(cfg));
        when(usageRepository.sumByCompanySince(any(), any())).thenReturn(null);

        CompanyAiConfigView view = service.get(companyId);

        assertEquals("unavailable", view.effectiveMode());
        assertFalse(view.fallbackEnabled());
    }

    @Test
    void enable_restoresStatusAndEvicts() {
        UUID companyId = UUID.randomUUID();
        CompanyAiConfig cfg = CompanyAiConfig.builder()
                .id(UUID.randomUUID()).companyId(companyId).provider("deepseek")
                .baseUrl("https://api.deepseek.com").apiKeyCipher("cipher").keyLast4("5678")
                .modelName("deepseek-chat").status("disabled").fallbackEnabled(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)).thenReturn(Optional.of(cfg));

        service.enable(companyId);

        assertEquals("enabled", cfg.getStatus());
        verify(repository).save(cfg);
        verify(resolver).evict(companyId);
        verify(historyRepository).save(any());
    }

    @Test
    void updateFirstTimeWithoutKey_rejects() {
        UUID companyId = UUID.randomUUID();
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)).thenReturn(Optional.empty());

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.update(companyId, new CompanyAiConfigRequest(
                        "https://api.deepseek.com", null, true, null, null)));
        assertEquals(400, e.getErrorCode());
        verify(repository, never()).save(any());
    }

    @Test
    void updateEncryptsAndEvicts() {
        UUID companyId = UUID.randomUUID();
        when(repository.save(any())).thenAnswer(inv -> {
            capturedConfig = inv.getArgument(0);
            return capturedConfig;
        });
        when(crypto.encrypt("sk-12345678")).thenReturn("cipher");
        when(usageRepository.sumByCompanySince(any(), any())).thenReturn(null);
        // update 先查（新建）→ 保存 → 再 get() 查一次
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId))
                .thenReturn(Optional.empty())
                .thenAnswer(inv -> Optional.ofNullable(capturedConfig));

        service.update(companyId, new CompanyAiConfigRequest(
                "https://api.deepseek.com", "sk-12345678", false, null, null));

        verify(crypto).encrypt("sk-12345678");
        verify(resolver).evict(companyId);
        verify(audit).record(anyString(), anyString(), any(), any());
    }

    private CompanyAiConfig capturedConfig;

    @Test
    void disable_setsStatusAndEvicts() {
        UUID companyId = UUID.randomUUID();
        CompanyAiConfig cfg = CompanyAiConfig.builder()
                .id(UUID.randomUUID()).companyId(companyId).provider("deepseek")
                .baseUrl("https://api.deepseek.com").apiKeyCipher("cipher").keyLast4("5678")
                .modelName("deepseek-chat").status("enabled").fallbackEnabled(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)).thenReturn(Optional.of(cfg));

        service.disable(companyId);

        assertEquals("disabled", cfg.getStatus());
        verify(repository).save(cfg);
        verify(resolver).evict(companyId);
    }

    @Test
    void rotateReplacesKeyWithoutRetainingOldCipher() {
        UUID companyId = UUID.randomUUID();
        CompanyAiConfig cfg = CompanyAiConfig.builder()
                .id(UUID.randomUUID()).companyId(companyId).provider("deepseek")
                .baseUrl("https://api.deepseek.com").apiKeyCipher("old-cipher").keyLast4("1111")
                .modelName("deepseek-chat").status("enabled").fallbackEnabled(true)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(repository.findFirstByCompanyIdOrderByUpdatedAtDesc(companyId)).thenReturn(Optional.of(cfg));
        when(crypto.encrypt("sk-new-key-2222")).thenReturn("new-cipher");

        service.rotate(companyId, "sk-new-key-2222");

        assertEquals("new-cipher", cfg.getApiKeyCipher());
        assertEquals("2222", cfg.getKeyLast4());
        verify(repository).save(cfg);
        verify(resolver).evict(companyId);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> detail =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("ai.model.rotate"), eq("company_ai_config"),
                eq(cfg.getId()), detail.capture());
        assertEquals("1111", detail.getValue().get("previousKeyLast4"));
        assertEquals("2222", detail.getValue().get("keyLast4"));
        assertEquals(false, detail.getValue().get("previousKeyRetained"));
    }

    @Test
    void testKey_maps401ToStableCode() {
        UUID companyId = UUID.randomUUID();
        when(factory.create(any(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("401 Unauthorized"));

        CompanyAiTestResult result = service.test(companyId,
                new CompanyAiTestRequest("sk-12345678", "https://api.deepseek.com", null, null));

        assertFalse(result.ok());
        assertEquals("AI_KEY_INVALID", result.errorCode());
    }

    @Test
    void testKey_success() {
        UUID companyId = UUID.randomUUID();
        ChatClient client = mock(ChatClient.class, withSettings().defaultAnswer(
                org.mockito.Answers.RETURNS_DEEP_STUBS));
        when(factory.create(any(), anyString(), anyString(), anyString(), anyString(), anyInt())).thenReturn(client);

        CompanyAiTestResult result = service.test(companyId,
                new CompanyAiTestRequest("sk-12345678", "https://api.deepseek.com", null, null));

        assertTrue(result.ok());
        assertEquals("连接成功", result.message());
    }
}
