package com.aiextract;

import com.aiextract.config.TokenContext;
import com.aiextract.dto.CoachLedgerRow;
import com.aiextract.model.ReviewReport;
import com.aiextract.model.ReviewSession;
import com.aiextract.model.User;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.ReviewSegmentRepository;
import com.aiextract.repository.ReviewSessionRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.SalesAccessService;
import com.aiextract.service.SalesCoachLedgerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesCoachLedgerServiceTest {

    @Mock private SalesAccessService access;
    @Mock private ReviewSessionRepository sessionRepository;
    @Mock private ReviewReportRepository reportRepository;
    @Mock private ReviewSegmentRepository segmentRepository;
    @Mock private UserRepository userRepository;

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void ledgerUsesOneBatchQueryAndKeepsLatestVersion() {
        UUID companyId = UUID.randomUUID();
        UUID me = UUID.randomUUID();
        TokenContext.set(me, companyId);
        when(access.requireCompanyId()).thenReturn(companyId);

        ReviewSession s1 = ReviewSession.builder().id(UUID.randomUUID()).companyId(companyId)
                .reviewerId(UUID.randomUUID()).coachOwnerId(me).status("report_ready")
                .createdAt(LocalDateTime.now()).build();
        ReviewSession s2 = ReviewSession.builder().id(UUID.randomUUID()).companyId(companyId)
                .reviewerId(UUID.randomUUID()).coachOwnerId(me).status("active")
                .createdAt(LocalDateTime.now()).build();
        when(sessionRepository.findByCompanyIdAndCoachOwnerIdOrderByCreatedAtDesc(
                any(), any(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(s1, s2)));
        when(userRepository.findAllById(anyList())).thenReturn(List.of(
                User.builder().id(s1.getReviewerId()).name("李娜").build(),
                User.builder().id(s2.getReviewerId()).name("王强").build()));

        ReviewReport v2 = ReviewReport.builder().id(UUID.randomUUID()).sessionId(s1.getId())
                .version(2).status("ready").summary("v2 摘要").build();
        ReviewReport v1 = ReviewReport.builder().id(UUID.randomUUID()).sessionId(s1.getId())
                .version(1).status("ready").summary("v1 摘要").build();
        when(reportRepository.findBySessionIdInOrderBySessionIdAscVersionDesc(anyList()))
                .thenReturn(List.of(v2, v1));

        SalesCoachLedgerService service = new SalesCoachLedgerService(access, sessionRepository,
                reportRepository, segmentRepository, userRepository);
        Page<CoachLedgerRow> page = service.ledger(1, 20);

        assertEquals("v2 摘要", page.getContent().get(0).summary());
        assertEquals(null, page.getContent().get(1).summary());
        verify(reportRepository, never())
                .findFirstBySessionIdOrderByVersionDesc(any());
        verify(reportRepository).findBySessionIdInOrderBySessionIdAscVersionDesc(anyList());
    }
}
