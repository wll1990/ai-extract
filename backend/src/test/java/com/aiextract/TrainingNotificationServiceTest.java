package com.aiextract;

import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.UserNotification;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.UserNotificationRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.TrainingNotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内信：写入、去重失败隔离、已读归属。
 */
@ExtendWith(MockitoExtension.class)
class TrainingNotificationServiceTest {

    @Mock private UserNotificationRepository notificationRepository;
    @Mock private TrainingCohortRepository cohortRepository;
    @Mock private UserRepository userRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private com.aiextract.service.ImPushService imPushService;

    private TrainingNotificationService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID notificationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingNotificationService(
                new com.aiextract.service.NotificationService(
                        notificationRepository, imPushService, transactionManager),
                cohortRepository, userRepository);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        TokenContext.set(userId, companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void notifyUser_writesNotification() {
        when(notificationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.notifyUser(userId, companyId, UserNotification.TYPE_CERTIFIED,
                "认证通过", "内容", "/training", "training_assignment", UUID.randomUUID(), "k1");

        ArgumentCaptor<UserNotification> captor = ArgumentCaptor.forClass(UserNotification.class);
        verify(notificationRepository).save(captor.capture());
        assertEquals(userId, captor.getValue().getUserId());
        assertEquals(companyId, captor.getValue().getCompanyId());
        assertEquals("k1", captor.getValue().getDedupeKey());
    }

    @Test
    void notifyUser_dedupeConflict_doesNotThrow() {
        when(notificationRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("uq_un_dedupe"));

        // 通知失败不能影响业务主流程
        service.notifyUser(userId, companyId, UserNotification.TYPE_CERTIFIED,
                "认证通过", "内容", null, null, null, "dup");

        verify(notificationRepository).save(any());
    }

    @Test
    void markRead_foreignNotification_notFound() {
        when(notificationRepository.findByIdAndUserId(notificationId, userId))
                .thenReturn(Optional.empty());

        assertEquals(404, assertThrows(BusinessException.class,
                () -> service.markRead(notificationId)).getErrorCode());
        verify(notificationRepository, never()).markRead(any(), any(), any());
    }

    @Test
    void markAllRead_onlyCurrentUser() {
        when(notificationRepository.markAllRead(eq(userId), any())).thenReturn(3);

        assertEquals(3, service.markAllRead());
        verify(notificationRepository).markAllRead(eq(userId), any());
    }

    @Test
    void unreadCount_queriesCurrentUser() {
        when(notificationRepository.countByUserIdAndReadAtIsNull(userId)).thenReturn(5L);

        assertEquals(5L, service.unreadCount());
    }
}
