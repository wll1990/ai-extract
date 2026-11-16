package com.aiextract;

import com.aiextract.config.TokenContext;
import com.aiextract.dto.ReviewCreateRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.FollowUpTask;
import com.aiextract.model.ReviewReport;
import com.aiextract.model.ReviewSession;
import com.aiextract.model.Skill;
import com.aiextract.model.Space;
import com.aiextract.repository.FollowUpTaskRepository;
import com.aiextract.repository.LeadRepository;
import com.aiextract.repository.ReviewReportRepository;
import com.aiextract.repository.ReviewSegmentRepository;
import com.aiextract.repository.ReviewSessionRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.ReviewSegmenter;
import com.aiextract.service.SalesAccessService;
import com.aiextract.service.SalesAuditService;
import com.aiextract.service.SalesReviewReportService;
import com.aiextract.service.SalesReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SalesReviewServiceTest {

    private SalesAccessService access;
    private ReviewSessionRepository sessionRepository;
    private ReviewSegmentRepository segmentRepository;
    private ReviewReportRepository reportRepository;
    private FollowUpTaskRepository taskRepository;
    private LeadRepository leadRepository;
    private SkillRepository skillRepository;
    private SpaceRepository spaceRepository;
    private UserRepository userRepository;
    private SalesReviewService service;

    private final UUID companyId = UUID.randomUUID();
    private final UUID reviewerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        access = mock(SalesAccessService.class);
        sessionRepository = mock(ReviewSessionRepository.class);
        segmentRepository = mock(ReviewSegmentRepository.class);
        reportRepository = mock(ReviewReportRepository.class);
        taskRepository = mock(FollowUpTaskRepository.class);
        leadRepository = mock(LeadRepository.class);
        skillRepository = mock(SkillRepository.class);
        spaceRepository = mock(SpaceRepository.class);
        userRepository = mock(UserRepository.class);
        service = new SalesReviewService(access, sessionRepository, segmentRepository,
                reportRepository, mock(SalesReviewReportService.class), taskRepository, leadRepository,
                skillRepository, spaceRepository,
                mock(com.aiextract.repository.ExperienceGrainRepository.class),
                userRepository, new ReviewSegmenter(),
                mock(SalesAuditService.class), new ObjectMapper(),
                mock(com.aiextract.service.FileStorageService.class),
                mock(com.aiextract.service.FileParseService.class),
                mock(com.aiextract.service.KnowledgeCaseService.class));
        ReflectionTestUtils.setField(service, "maxTranscriptChars", 30000);
        TokenContext.set(reviewerId, companyId);
        when(access.requireCompanyId()).thenReturn(companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void createRejectsCoachFromAnotherCompany() {
        UUID coachId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        Skill coach = Skill.builder().id(coachId).spaceId(spaceId).type("individual")
                .status("published").companyId(UUID.randomUUID()).build();
        when(skillRepository.findById(coachId)).thenReturn(Optional.of(coach));
        when(spaceRepository.findById(spaceId)).thenReturn(Optional.of(
                Space.builder().id(spaceId).userId(UUID.randomUUID()).build()));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.create(new ReviewCreateRequest(coachId, "客户：你好", null, null, false, null)));
        assertEquals(404, e.getErrorCode());
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void requestReportReturnsExistingActiveJob() {
        UUID sessionId = UUID.randomUUID();
        ReviewSession session = session(sessionId);
        ReviewReport active = ReviewReport.builder().id(UUID.randomUUID())
                .sessionId(sessionId).status("queued").version(1).build();
        when(sessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(access.requireSession(sessionId)).thenReturn(session);
        when(reportRepository.findFirstBySessionIdAndStatusInOrderByVersionDesc(
                sessionId, List.of("queued", "generating"))).thenReturn(Optional.of(active));

        UUID id = service.requestReport(sessionId);

        assertEquals(active.getId(), id);
        verify(reportRepository, never()).save(any());
    }

    @Test
    void updateShareOnlyByReviewer() {
        UUID sessionId = UUID.randomUUID();
        ReviewSession session = session(sessionId);
        session.setReviewerId(UUID.randomUUID());
        when(access.requireSession(sessionId)).thenReturn(session);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.updateShare(sessionId, true));
        assertEquals(403, e.getErrorCode());
    }

    @Test
    void previousReturnsNullWhenReviewHasNoLead() {
        UUID id = UUID.randomUUID();
        when(access.requireSession(id)).thenReturn(session(id));

        assertNull(service.previous(id));
    }

    @Test
    void previousReturnsEarlierReviewOfSameLead() {
        UUID id = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();
        ReviewSession current = session(id);
        current.setLeadId(leadId);
        ReviewSession prev = session(UUID.randomUUID());
        prev.setLeadId(leadId);
        prev.setCustomerName("华东制造");
        prev.setCreatedAt(current.getCreatedAt().minusDays(3));
        ReviewReport report = ReviewReport.builder().id(UUID.randomUUID())
                .sessionId(prev.getId()).status("ready").version(1)
                .summary("客户在比价")
                .payload("{\"customerSignals\":[{\"field\":\"预算\",\"value\":\"30万\"}]}")
                .build();
        FollowUpTask task = FollowUpTask.builder().id(UUID.randomUUID()).companyId(companyId)
                .leadId(leadId).reviewId(prev.getId()).ownerId(reviewerId)
                .title("确认预算").status("done")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(access.requireSession(id)).thenReturn(current);
        when(access.canViewAll()).thenReturn(false);
        when(sessionRepository
                .findFirstByCompanyIdAndLeadIdAndReviewerIdAndIdNotAndCreatedAtBeforeOrderByCreatedAtDesc(
                        companyId, leadId, reviewerId, id, current.getCreatedAt()))
                .thenReturn(Optional.of(prev));
        when(reportRepository.findFirstBySessionIdOrderByVersionDesc(prev.getId()))
                .thenReturn(Optional.of(report));
        when(taskRepository.findByReviewIdOrderByCreatedAtAsc(prev.getId()))
                .thenReturn(List.of(task));

        var view = service.previous(id);

        assertEquals(prev.getId(), view.sessionId());
        assertEquals("华东制造", view.customerName());
        assertEquals("30万", view.customerSignals().get(0).path("value").asText());
        assertEquals("done", view.tasks().get(0).status());
    }

    private ReviewSession session(UUID id) {
        LocalDateTime now = LocalDateTime.now();
        return ReviewSession.builder()
                .id(id).companyId(companyId).reviewerId(reviewerId)
                .coachSkillId(UUID.randomUUID()).coachOwnerId(UUID.randomUUID())
                .transcript("客户：你好").transcriptChars(5).source("text")
                .shareTranscriptWithCoach(false).status("active")
                .createdAt(now).updatedAt(now).build();
    }
}
