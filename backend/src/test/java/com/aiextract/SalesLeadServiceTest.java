package com.aiextract;

import com.aiextract.config.TokenContext;
import com.aiextract.dto.LeadContactRequest;
import com.aiextract.dto.LeadConvertRequest;
import com.aiextract.dto.LeadUpdateRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.FollowUpTask;
import com.aiextract.model.Lead;
import com.aiextract.model.LeadContact;
import com.aiextract.model.ReviewReport;
import com.aiextract.model.ReviewSession;
import com.aiextract.repository.FollowUpTaskRepository;
import com.aiextract.repository.LeadActivityRepository;
import com.aiextract.repository.LeadContactRepository;
import com.aiextract.repository.LeadRepository;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.ReviewSessionRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.LeadActivityService;
import com.aiextract.service.SalesAccessService;
import com.aiextract.service.SalesAuditService;
import com.aiextract.service.SalesLeadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesLeadServiceTest {

    @Mock private SalesAccessService access;
    @Mock private ReviewSessionRepository sessionRepository;
    @Mock private ReviewReportRepository reportRepository;
    @Mock private LeadRepository leadRepository;
    @Mock private LeadContactRepository contactRepository;
    @Mock private LeadActivityRepository activityRepository;
    @Mock private FollowUpTaskRepository taskRepository;
    @Mock private UserRepository userRepository;
    @Mock private LeadActivityService activityService;
    @Mock private SalesAuditService audit;

    private SalesLeadService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SalesLeadService(access, sessionRepository, reportRepository, leadRepository,
                contactRepository, activityRepository, taskRepository, userRepository,
                activityService, audit);
        TokenContext.set(userId, companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    private Lead lead() {
        return Lead.builder().id(UUID.randomUUID()).companyId(companyId).ownerId(userId)
                .name("华星科技").nameNormalized("华星科技").status("new").source("review")
                .createdBy(userId).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    private ReviewSession session(UUID sessionId) {
        return ReviewSession.builder()
                .id(sessionId).companyId(companyId).reviewerId(userId)
                .coachSkillId(UUID.randomUUID()).status("report_ready")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private ReviewReport report(UUID sessionId, String status) {
        return ReviewReport.builder()
                .id(UUID.randomUUID()).companyId(companyId).sessionId(sessionId)
                .version(2).status(status).attempts(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }

    private LeadConvertRequest convertRequest() {
        return new LeadConvertRequest("华星科技", "王总", "13800001111", "需要预算", false);
    }

    @Test
    void convertRejectsWhenLatestReportNotReady() {
        UUID sessionId = UUID.randomUUID();
        ReviewSession session = session(sessionId);
        when(access.requireSession(sessionId)).thenReturn(session);
        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(reportRepository.findFirstBySessionIdOrderByVersionDesc(sessionId))
                .thenReturn(Optional.of(report(sessionId, "generating")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.convertFromReview(sessionId, convertRequest()));
        assertEquals(409, ex.getErrorCode());
    }

    @Test
    void convertAttachesTasksCreatedBeforeLead() {
        UUID sessionId = UUID.randomUUID();
        ReviewSession session = session(sessionId);
        when(access.requireSession(sessionId)).thenReturn(session);
        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(reportRepository.findFirstBySessionIdOrderByVersionDesc(sessionId))
                .thenReturn(Optional.of(report(sessionId, "ready")));
        when(leadRepository.findDuplicates(any(), any(), any(), anyInt())).thenReturn(List.of());
        FollowUpTask detached = FollowUpTask.builder()
                .id(UUID.randomUUID()).companyId(companyId).reviewId(sessionId)
                .ownerId(userId).title("确认预算").status("pending")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(taskRepository.findByReviewIdAndLeadIdIsNull(sessionId))
                .thenReturn(List.of(detached));

        service.convertFromReview(sessionId, convertRequest());

        assertEquals(session.getLeadId(), detached.getLeadId());
        verify(taskRepository).saveAll(List.of(detached));
        verify(activityService).record(eq(session.getLeadId()), eq("task_created"),
                anyString(), eq("review_session"), eq(sessionId));
    }

    @Test
    void addPrimaryContactClearsPreviousAndSyncsLead() {
        Lead lead = lead();
        when(access.requireLead(lead.getId())).thenReturn(lead);

        var view = service.addContact(lead.getId(), new LeadContactRequest(
                "王总", "13800001111", "decision_maker", true, "关键决策人"));

        assertEquals("王总", view.name());
        assertEquals(true, view.primary());
        verify(contactRepository).clearPrimary(eq(lead.getId()), any());
        assertEquals("王总", lead.getContactName());
        assertEquals("13800001111", lead.getContactPhone());
        verify(activityService).record(eq(lead.getId()), eq("contact_added"),
                anyString(), eq("lead_contact"), any());
    }

    @Test
    void deletePrimaryContactClearsLeadSnapshot() {
        Lead lead = lead();
        lead.setContactName("王总");
        lead.setContactPhone("13800001111");
        LeadContact contact = LeadContact.builder().id(UUID.randomUUID()).companyId(companyId)
                .leadId(lead.getId()).name("王总").primary(true).build();
        when(access.requireLead(lead.getId())).thenReturn(lead);
        when(contactRepository.findByIdAndCompanyIdAndLeadId(contact.getId(), companyId, lead.getId()))
                .thenReturn(Optional.of(contact));
        when(contactRepository.findByCompanyIdAndLeadIdOrderByPrimaryDescCreatedAtAsc(
                companyId, lead.getId())).thenReturn(List.of());

        service.deleteContact(lead.getId(), contact.getId());

        assertNull(lead.getContactName());
        assertNull(lead.getContactPhone());
        verify(contactRepository).delete(contact);
    }

    @Test
    void updateRejectsDuplicateName() {
        Lead lead = lead();
        when(access.requireLead(lead.getId())).thenReturn(lead);
        when(leadRepository.findDuplicatesExcluding(eq(companyId), anyString(), eq(null),
                eq(lead.getId()), eq(5)))
                .thenReturn(List.of(lead()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(lead.getId(),
                        new LeadUpdateRequest("另一个名字", null, null)));
        assertEquals("LEAD_DUPLICATE", ex.getSubCode());
    }

    @Test
    void updateStatusWritesTimeline() {
        Lead lead = lead();
        when(access.requireLead(lead.getId())).thenReturn(lead);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        when(leadRepository.updateStatusIfCurrent(eq(lead.getId()), eq("new"), eq("following"),
                any())).thenReturn(1);

        var summary = service.updateStatus(lead.getId(), "following");

        assertEquals("following", summary.status());
        verify(activityService).record(eq(lead.getId()), eq("status_changed"),
                anyString(), eq("lead"), eq(lead.getId()));
        verify(audit).record(eq("sales.lead.status"), eq("lead"), eq(lead.getId()), any());
    }

    @Test
    void updateStatusRejectsIllegalTransition() {
        Lead lead = lead();
        lead.setStatus("converted");
        when(access.requireLead(lead.getId())).thenReturn(lead);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateStatus(lead.getId(), "following"));
        assertEquals("LEAD_STATE_INVALID", ex.getSubCode());
    }
}
