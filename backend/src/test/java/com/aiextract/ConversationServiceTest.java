package com.aiextract;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.SkillConversation;
import com.aiextract.model.User;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.ReportRepository;
import com.aiextract.repository.SkillConversationRepository;
import com.aiextract.repository.SkillMessageRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话重命名：属主可改、非属主 403、空标题 400（CH-02）。
 */
@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock private SkillConversationRepository conversationRepository;
    @Mock private SkillMessageRepository messageRepository;
    @Mock private UserRepository userRepository;
    @Mock private ExperienceGrainRepository grainRepository;
    @Mock private ReportRepository reportRepository;

    private ConversationService service;
    private final UUID ownerId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ConversationService(conversationRepository, messageRepository,
                userRepository, grainRepository, reportRepository,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private SkillConversation conversation(UUID owner) {
        return SkillConversation.builder()
                .id(UUID.randomUUID()).skillId(UUID.randomUUID()).userId(owner)
                .title("旧标题").mode("qa")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    void ownerCanRenameAndTitleIsTrimmed() {
        SkillConversation conv = conversation(ownerId);
        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));

        Map<String, Object> result = service.renameConversation(conv.getId().toString(), "  新标题  ", ownerId);

        assertEquals("新标题", result.get("title"));
        assertEquals("新标题", conv.getTitle());
        verify(conversationRepository).save(conv);
    }

    @Test
    void nonOwnerWithoutPermissionIsRejected() {
        SkillConversation conv = conversation(ownerId);
        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));
        User employee = User.builder().id(otherId).role("employee").build();
        when(userRepository.findById(otherId)).thenReturn(Optional.of(employee));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.renameConversation(conv.getId().toString(), "改名", otherId));
        assertEquals(403, ex.getErrorCode());
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void blankTitleIsRejected() {
        SkillConversation conv = conversation(ownerId);
        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.renameConversation(conv.getId().toString(), "   ", ownerId));
        assertEquals(400, ex.getErrorCode());
    }

    @Test
    void longTitleIsRejected() {
        SkillConversation conv = conversation(ownerId);
        when(conversationRepository.findById(conv.getId())).thenReturn(Optional.of(conv));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.renameConversation(conv.getId().toString(), "x".repeat(201), ownerId));
        assertEquals(400, ex.getErrorCode());
    }
}
