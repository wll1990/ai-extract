package com.aiextract;

import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.service.TrainingGradingLeaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TrainingGradingLeaseService 单元测试 —— 领取边界与续租委托。
 */
@ExtendWith(MockitoExtension.class)
class TrainingGradingLeaseServiceTest {

    @Mock private TrainingAttemptRepository attemptRepository;

    private TrainingGradingLeaseService service;
    private static final String OWNER = "worker-1";

    @BeforeEach
    void setUp() {
        service = new TrainingGradingLeaseService(attemptRepository);
        ReflectionTestUtils.setField(service, "leaseSeconds", 900);
    }

    @Test
    void claim_zeroLimit_returnsEmptyWithoutQuery() {
        assertTrue(service.claim(OWNER, 0).isEmpty());
        verify(attemptRepository, never()).lockClaimableIds(anyInt());
    }

    @Test
    void claim_locksAndClaimsInSameCall() {
        UUID id = UUID.randomUUID();
        when(attemptRepository.lockClaimableIds(5)).thenReturn(List.of(id));
        when(attemptRepository.claim(List.of(id), OWNER, 900)).thenReturn(1);

        List<UUID> claimed = service.claim(OWNER, 5);

        assertEquals(List.of(id), claimed);
        verify(attemptRepository).claim(eq(List.of(id)), eq(OWNER), eq(900));
    }

    @Test
    void claim_noRows_skipsUpdate() {
        when(attemptRepository.lockClaimableIds(5)).thenReturn(List.of());

        assertTrue(service.claim(OWNER, 5).isEmpty());
        verify(attemptRepository, never()).claim(any(), any(), anyInt());
    }

    @Test
    void renewAll_delegatesWithLeaseSeconds() {
        when(attemptRepository.renewLeases(OWNER, 900)).thenReturn(2);

        assertEquals(2, service.renewAll(OWNER));
    }
}
