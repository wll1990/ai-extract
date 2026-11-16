package com.aiextract;

import com.aiextract.config.TokenRevocationService;
import com.aiextract.model.User;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.UserSecurityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserSecurityService 单元测试 —— 版本号自增与「提交后才写 Redis」的时序。
 */
@ExtendWith(MockitoExtension.class)
class UserSecurityServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private TokenRevocationService revocationService;

    private UserSecurityService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UserSecurityService(userRepository, revocationService);
        clearIfActive();
    }

    @AfterEach
    void tearDown() {
        clearIfActive();
    }

    /** clearSynchronization 在未激活时会抛异常，必须先判断 */
    private void clearIfActive() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void onAuthoritiesChanged_withManagedEntity_bumpsAndCaches() {
        User user = User.builder().id(userId).role("employee").tokenVersion(4L).build();

        service.onAuthoritiesChanged(user);

        assertEquals(5L, user.getTokenVersion());
        // 无事务同步时立即写缓存
        verify(revocationService).cacheVersion(userId, 5L);
    }

    @Test
    void onAuthoritiesChanged_withActiveTransaction_defersCacheUntilCommit() {
        TransactionSynchronizationManager.initSynchronization();
        User user = User.builder().id(userId).tokenVersion(0L).build();

        service.onAuthoritiesChanged(user);

        assertEquals(1L, user.getTokenVersion(), "DB 版本号在事务内立即生效");
        verify(revocationService, never()).cacheVersion(eq(userId), anyLong());

        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        verify(revocationService).cacheVersion(userId, 1L);
    }

    @Test
    void onAuthoritiesChanged_byId_usesJpqlIncrementResult() {
        when(userRepository.bumpTokenVersion(userId)).thenReturn(1);
        when(userRepository.findTokenVersionById(userId)).thenReturn(9L);

        service.onAuthoritiesChanged(userId);

        verify(revocationService).cacheVersion(userId, 9L);
    }

    @Test
    void onAuthoritiesChanged_missingUser_doesNotCache() {
        when(userRepository.bumpTokenVersion(userId)).thenReturn(0);

        service.onAuthoritiesChanged(userId);

        verify(revocationService, never()).cacheVersion(eq(userId), anyLong());
    }

    @Test
    void cacheWriteFailure_doesNotThrow() {
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(revocationService).cacheVersion(any(UUID.class), anyLong());
        User user = User.builder().id(userId).tokenVersion(1L).build();

        // 不抛异常：Redis 写失败不能影响业务主流程
        service.onAuthoritiesChanged(user);

        assertEquals(2L, user.getTokenVersion());
    }
}
