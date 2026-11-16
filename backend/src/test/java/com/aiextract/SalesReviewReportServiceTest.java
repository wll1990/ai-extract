package com.aiextract;

import com.aiextract.model.ReviewReport;
import com.aiextract.model.ReviewSession;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.ReviewSessionRepository;
import com.aiextract.service.SalesAuditService;
import com.aiextract.service.SalesReviewReportService;
import com.aiextract.service.TrainingNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 报告租约耗尽清理 —— P1-1。
 */
@ExtendWith(MockitoExtension.class)
class SalesReviewReportServiceTest {

    @Mock private ReviewReportRepository reportRepository;
    @Mock private ReviewSessionRepository sessionRepository;
    @Mock private TrainingNotificationService notificationService;
    @Mock private SalesAuditService audit;

    private SalesReviewReportService service;

    @BeforeEach
    void setUp() {
        service = new SalesReviewReportService(reportRepository, sessionRepository,
                notificationService, audit);
        ReflectionTestUtils.setField(service, "maxAttempts", 3);
        ReflectionTestUtils.setField(service, "leaseMinutes", 10);
    }

    @Test
    void expiredExhaustedReportIsMarkedFailedAndNotified() {
        UUID companyId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        ReviewReport report = report(companyId, 3, LocalDateTime.now().minusMinutes(1));
        when(reportRepository.expireStale(report.getId(), 3)).thenReturn(1);
        when(sessionRepository.findById(report.getSessionId()))
                .thenReturn(Optional.of(session(companyId, reviewerId)));

        assertTrue(service.expireIfStale(report));

        verify(notificationService).notifyUser(any(), any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void liveLeaseIsNotExpired() {
        ReviewReport report = report(UUID.randomUUID(), 3, LocalDateTime.now().plusMinutes(5));

        assertFalse(service.expireIfStale(report));
        verify(reportRepository, never()).expireStale(any(), anyInt());
    }

    private ReviewReport report(UUID companyId, int attempts, LocalDateTime leaseExpiresAt) {
        return ReviewReport.builder()
                .id(UUID.randomUUID()).companyId(companyId).sessionId(UUID.randomUUID())
                .version(1).status("generating").attempts(attempts)
                .leaseExpiresAt(leaseExpiresAt)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private ReviewSession session(UUID companyId, UUID reviewerId) {
        return ReviewSession.builder()
                .id(UUID.randomUUID()).companyId(companyId).reviewerId(reviewerId)
                .coachSkillId(UUID.randomUUID()).status("report_ready")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
