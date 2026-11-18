package com.aiextract;

import com.aiextract.model.User;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.UserSecurityService;
import com.aiextract.service.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserSecurityService 单元测试 —— 版本号自增后必须撤销全部会话。
 */
@ExtendWith(MockitoExtension.class)
class UserSecurityServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private SessionService sessionService;

    private UserSecurityService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UserSecurityService(userRepository, sessionService);
    }

    @Test
    void onAuthoritiesChanged_withManagedEntity_bumpsAndRevokesAll() {
        User user = User.builder().id(userId).role("employee").tokenVersion(4L).build();

        service.onAuthoritiesChanged(user);

        assertEquals(5L, user.getTokenVersion());
        verify(sessionService).revokeAllWithEpoch(userId, 5L);
    }

    @Test
    void onAuthoritiesChanged_byId_usesJpqlIncrementResult() {
        when(userRepository.bumpTokenVersion(userId)).thenReturn(1);
        when(userRepository.findTokenVersionById(userId)).thenReturn(9L);

        service.onAuthoritiesChanged(userId);

        verify(sessionService).revokeAllWithEpoch(userId, 9L);
    }

    @Test
    void onAuthoritiesChanged_missingUser_doesNotRevoke() {
        when(userRepository.bumpTokenVersion(userId)).thenReturn(0);

        service.onAuthoritiesChanged(userId);

        verify(sessionService, never()).revokeAllWithEpoch(eq(userId), anyLong());
    }

    @Test
    void revokeFailure_propagates() {
        org.mockito.Mockito.doThrow(new RuntimeException("session store down"))
                .when(sessionService).revokeAllWithEpoch(any(UUID.class), anyLong());
        User user = User.builder().id(userId).tokenVersion(1L).build();

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.onAuthoritiesChanged(user));
        assertEquals(2L, user.getTokenVersion());
    }
}
