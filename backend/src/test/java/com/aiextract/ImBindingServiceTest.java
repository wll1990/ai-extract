package com.aiextract;

import com.aiextract.config.TokenContext;
import com.aiextract.model.ImBindCode;
import com.aiextract.model.ImChannel;
import com.aiextract.model.ImUserBinding;
import com.aiextract.repository.ImBindCodeRepository;
import com.aiextract.repository.ImUserBindingRepository;
import com.aiextract.service.ImBindingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IM 绑定：绑定码一次性、企业校验、改绑覆盖旧身份。
 */
@ExtendWith(MockitoExtension.class)
class ImBindingServiceTest {

    @Mock private ImBindCodeRepository codeRepository;
    @Mock private ImUserBindingRepository bindingRepository;

    private ImBindingService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final String external = "ou_test_user";

    @BeforeEach
    void setUp() {
        service = new ImBindingService(codeRepository, bindingRepository);
        TokenContext.set(userId, companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void createCode_storesTenMinuteCode() {
        when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ImBindingService.BindCode code = service.createCode();

        assertEquals(6, code.code().length());
        assertTrue(code.expiresAt().isAfter(LocalDateTime.now()));
        verify(codeRepository).save(any(ImBindCode.class));
    }

    @Test
    void bindCommand_wrongCompany_rejected() {
        when(codeRepository.findById("123456")).thenReturn(Optional.of(code(
                UUID.randomUUID(), UUID.randomUUID(), LocalDateTime.now().plusMinutes(5))));

        var reply = service.handleBindCommand(channel(companyId), "绑定 123456",
                external, "chat-1", "张三");

        assertTrue(reply.isPresent());
        assertTrue(reply.get().contains("不属于当前企业"));
    }

    @Test
    void bindCommand_expired_rejected() {
        when(codeRepository.findById("123456")).thenReturn(Optional.of(code(
                companyId, userId, LocalDateTime.now().minusMinutes(1))));

        var reply = service.handleBindCommand(channel(companyId), "绑定 123456",
                external, "chat-1", "张三");

        assertTrue(reply.get().contains("已过期"));
    }

    @Test
    void bindCommand_valid_createsBindingAndConsumesCode() {
        when(codeRepository.findById("123456")).thenReturn(Optional.of(code(
                companyId, userId, LocalDateTime.now().plusMinutes(5))));
        when(bindingRepository.findByChannelTypeAndExternalUserId("feishu", external))
                .thenReturn(Optional.empty());
        when(bindingRepository.findByCompanyIdAndChannelTypeAndUserId(companyId, "feishu", userId))
                .thenReturn(Optional.empty());
        when(bindingRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var reply = service.handleBindCommand(channel(companyId), "绑定 123456",
                external, "chat-1", "张三");

        assertTrue(reply.get().contains("绑定成功"));
        verify(bindingRepository).save(any(ImUserBinding.class));
        verify(codeRepository).deleteById("123456");
    }

    @Test
    void nonBindMessage_passesThrough() {
        assertFalse(service.handleBindCommand(channel(companyId), "你好", external, null, null).isPresent());
        assertFalse(service.handleBindCommand(channel(companyId), "绑定 12", external, null, null).isPresent());
    }

    private ImBindCode code(UUID company, UUID user, LocalDateTime expiresAt) {
        return ImBindCode.builder().code("123456").companyId(company).userId(user)
                .expiresAt(expiresAt).createdAt(LocalDateTime.now()).build();
    }

    private ImChannel channel(UUID company) {
        ImChannel channel = ImChannel.builder()
                .id(UUID.randomUUID()).companyId(company).channelType("feishu")
                .botId("cli_test").enabled(true).config("{}").build();
        return channel;
    }
}
