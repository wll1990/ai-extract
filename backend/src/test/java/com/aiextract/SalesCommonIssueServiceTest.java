package com.aiextract;

import com.aiextract.config.Permission;
import com.aiextract.dto.KnowledgeEntryRequest;
import com.aiextract.dto.SalesCommonIssueView;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.model.ReviewReport;
import com.aiextract.model.ReviewSession;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.ReviewSessionRepository;
import com.aiextract.service.KnowledgeCaseService;
import com.aiextract.service.SalesAccessService;
import com.aiextract.service.SalesAuditService;
import com.aiextract.service.SalesCommonIssueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesCommonIssueServiceTest {

    private SalesAccessService access;
    private ReviewReportRepository reportRepository;
    private ReviewSessionRepository sessionRepository;
    private KnowledgeCaseService knowledgeCaseService;
    private KnowledgeEntryRepository knowledgeRepository;
    private SalesCommonIssueService service;

    private final UUID companyId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        access = mock(SalesAccessService.class);
        reportRepository = mock(ReviewReportRepository.class);
        sessionRepository = mock(ReviewSessionRepository.class);
        knowledgeCaseService = mock(KnowledgeCaseService.class);
        knowledgeRepository = mock(KnowledgeEntryRepository.class);
        service = new SalesCommonIssueService(access, reportRepository, sessionRepository,
                knowledgeCaseService, knowledgeRepository,
                mock(SalesAuditService.class), new ObjectMapper());
        when(access.requireCompanyId()).thenReturn(companyId);
    }

    private ReviewSession session(UUID id, String customerName) {
        return ReviewSession.builder()
                .id(id).companyId(companyId).reviewerId(reviewerId)
                .customerName(customerName).status("report_ready")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private ReviewReport report(UUID sessionId, String payload) {
        return ReviewReport.builder()
                .id(UUID.randomUUID()).companyId(companyId).sessionId(sessionId)
                .version(1).status("ready").payload(payload)
                .generatedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private void stubReports(ReviewReport... reports) {
        when(reportRepository.findByCompanyIdAndStatusAndGeneratedAtAfter(
                eq(companyId), eq("ready"), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(reports)));
        when(sessionRepository.findAllById(any())).thenReturn(List.of(
                session(reports[0].getSessionId(), "云海集团"),
                session(reports.length > 1 ? reports[1].getSessionId() : reports[0].getSessionId(), "华星科技")));
    }

    @Test
    void aggregatesRecurringIssuesAcrossReports() {
        when(access.canViewAll()).thenReturn(true);
        UUID s1 = UUID.randomUUID();
        UUID s2 = UUID.randomUUID();
        stubReports(
                report(s1, """
                        {"weaknesses":[{"point":"没有确认预算","suggestion":"先问预算范围"}],
                         "risks":[{"point":"决策人不清楚"}]}
                        """),
                report(s2, """
                        {"weaknesses":[{"point":"价格被压时只会降价","suggestion":"用三年总成本对比"}],
                         "risks":[]}
                        """));

        SalesCommonIssueView view = service.commonIssues(90);

        assertEquals(2, view.reportCount());
        SalesCommonIssueView.Issue price = view.issues().stream()
                .filter(i -> i.key().equals("price")).findFirst().orElseThrow();
        assertEquals(2, price.reportCount());
        assertEquals(2, price.pointCount());
        assertTrue(price.samples().size() >= 1);
        SalesCommonIssueView.Issue decision = view.issues().stream()
                .filter(i -> i.key().equals("decision")).findFirst().orElseThrow();
        assertEquals(1, decision.reportCount());
        // 按涉及复盘数降序
        assertEquals("price", view.issues().get(0).key());
        // 企业隔离：查询必须带当前 companyId
        verify(reportRepository).findByCompanyIdAndStatusAndGeneratedAtAfter(
                eq(companyId), eq("ready"), any(LocalDateTime.class), any(Pageable.class));
    }

    @Test
    void countsOneReportOnceWhenItHasMultiplePointsInSameCategory() {
        when(access.canViewAll()).thenReturn(true);
        UUID sessionId = UUID.randomUUID();
        stubReports(report(sessionId, """
                {"weaknesses":[{"point":"没有确认预算"},{"point":"报价没有对比"}],"risks":[]}
                """));

        SalesCommonIssueView view = service.commonIssues(90);
        SalesCommonIssueView.Issue price = view.issues().stream()
                .filter(i -> i.key().equals("price")).findFirst().orElseThrow();

        assertEquals(1, price.reportCount());
        assertEquals(2, price.pointCount());
    }

    @Test
    void ignoresPointsWithoutCategoryKeywords() {
        when(access.canViewAll()).thenReturn(true);
        stubReports(report(UUID.randomUUID(), """
                {"weaknesses":[{"point":"态度不够热情"}],"risks":[]}
                """));

        assertTrue(service.commonIssues(90).issues().isEmpty());
    }

    @Test
    void skipsCorruptPayloadWithoutFailingRequest() {
        when(access.canViewAll()).thenReturn(true);
        stubReports(report(UUID.randomUUID(), "{not-json"));

        SalesCommonIssueView view = service.commonIssues(90);

        assertEquals(1, view.reportCount());
        assertTrue(view.issues().isEmpty());
    }

    @Test
    void clampsPeriodDays() {
        when(access.canViewAll()).thenReturn(true);
        when(reportRepository.findByCompanyIdAndStatusAndGeneratedAtAfter(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        assertEquals(90, service.commonIssues(0).periodDays());
        assertEquals(365, service.commonIssues(999).periodDays());
    }

    @Test
    void unknownCategoryReturns404() {
        when(access.canViewAll()).thenReturn(true);
        when(access.hasAuthority(Permission.KNOWLEDGE_MANAGE)).thenReturn(true);
        when(reportRepository.findByCompanyIdAndStatusAndGeneratedAtAfter(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.createTrainingKnowledge("nope", 90));
        assertEquals(404, e.getErrorCode());
    }

    @Test
    void reusesExistingPendingDraft() {
        when(access.canViewAll()).thenReturn(true);
        when(access.hasAuthority(Permission.KNOWLEDGE_MANAGE)).thenReturn(true);
        stubReports(report(UUID.randomUUID(), """
                {"weaknesses":[{"point":"预算没有确认"}],"risks":[]}
                """));
        KnowledgeEntry existing = KnowledgeEntry.builder()
                .id(UUID.randomUUID()).companyId(companyId)
                .title("培训主题：预算与价格").status(KnowledgeEntry.STATUS_PENDING).build();
        when(knowledgeRepository.findFirstByCompanyIdAndTitleAndStatusOrderByCreatedAtDesc(
                companyId, "培训主题：预算与价格", KnowledgeEntry.STATUS_PENDING))
                .thenReturn(java.util.Optional.of(existing));

        Map<String, Object> result = service.createTrainingKnowledge("price", 90);

        assertEquals(existing.getId().toString(), result.get("id"));
        assertEquals(true, result.get("reused"));
        verify(knowledgeCaseService, never()).createKnowledge(any(), any());
    }

    @Test
    void rejectsEmployeeWithoutCompanyViewPermission() {
        when(access.canViewAll()).thenReturn(false);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.commonIssues(90));
        assertEquals(403, e.getErrorCode());
    }

    @Test
    void createsPendingKnowledgeDraftFromIssue() {
        when(access.canViewAll()).thenReturn(true);
        when(access.hasAuthority(Permission.KNOWLEDGE_MANAGE)).thenReturn(true);
        UUID sessionId = UUID.randomUUID();
        stubReports(report(sessionId, """
                {"weaknesses":[{"point":"报价后没有确认预算","suggestion":"追问预算与审批人"}],
                 "risks":[]}
                """));
        KnowledgeEntry entry = KnowledgeEntry.builder()
                .id(UUID.randomUUID()).title("培训主题：预算与价格").build();
        when(knowledgeCaseService.createKnowledge(any(), eq(companyId))).thenReturn(entry);

        Map<String, Object> result = service.createTrainingKnowledge("price", 90);

        assertEquals(entry.getId().toString(), result.get("id"));
        ArgumentCaptor<KnowledgeEntryRequest> captor =
                ArgumentCaptor.forClass(KnowledgeEntryRequest.class);
        verify(knowledgeCaseService).createKnowledge(captor.capture(), eq(companyId));
        assertEquals("培训主题：预算与价格", captor.getValue().getTitle());
        assertEquals(KnowledgeEntry.STATUS_PENDING, captor.getValue().getStatus());
        assertTrue(captor.getValue().getContent().contains("共性问题：预算与价格"));
        assertTrue(captor.getValue().getContent().contains("报价后没有确认预算"));
    }
}
