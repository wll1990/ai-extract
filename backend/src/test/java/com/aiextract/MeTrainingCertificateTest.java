package com.aiextract;

import com.aiextract.config.TokenContext;
import com.aiextract.controller.MeTrainingController;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingCertification;
import com.aiextract.model.TrainingCohort;
import com.aiextract.model.TrainingPlan;
import com.aiextract.model.User;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingCertificationRepository;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.TrainingPlanRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.TrainingAssignmentService;
import com.aiextract.service.TrainingLearningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 认证证书下载边界（TR-05）：本人 + 已认证才可下载。
 */
@ExtendWith(MockitoExtension.class)
class MeTrainingCertificateTest {

    @Mock private TrainingAssignmentService assignmentService;
    @Mock private TrainingLearningService learningService;
    @Mock private TrainingAssignmentRepository assignmentRepository;
    @Mock private TrainingCertificationRepository certificationRepository;
    @Mock private TrainingCohortRepository cohortRepository;
    @Mock private TrainingPlanRepository planRepository;
    @Mock private UserRepository userRepository;

    private MeTrainingController controller;
    private final UUID learnerId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();
    private final UUID cohortId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new MeTrainingController(assignmentService, learningService,
                assignmentRepository, certificationRepository, cohortRepository,
                planRepository, userRepository);
        TokenContext.set(learnerId, companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    private TrainingAssignment assignment(UUID learner) {
        return TrainingAssignment.builder()
                .id(assignmentId).companyId(companyId).cohortId(cohortId).learnerId(learner)
                .build();
    }

    @Test
    void otherLearnerCannotDownload() {
        when(assignmentRepository.findById(assignmentId))
                .thenReturn(Optional.of(assignment(UUID.randomUUID())));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.certificate(assignmentId));
        assertEquals(403, ex.getErrorCode());
    }

    @Test
    void unconfirmedAssignmentReturnsConflict() {
        when(assignmentRepository.findById(assignmentId))
                .thenReturn(Optional.of(assignment(learnerId)));
        when(certificationRepository.findByAssignmentIdAndStatus(assignmentId, "confirmed"))
                .thenReturn(Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.certificate(assignmentId));
        assertEquals(409, ex.getErrorCode());
    }

    @Test
    void confirmedAssignmentReturnsPrintableHtml() {
        when(assignmentRepository.findById(assignmentId))
                .thenReturn(Optional.of(assignment(learnerId)));
        when(certificationRepository.findByAssignmentIdAndStatus(assignmentId, "confirmed"))
                .thenReturn(Optional.of(TrainingCertification.builder()
                        .id(UUID.randomUUID()).assignmentId(assignmentId)
                        .learnerId(learnerId).status("confirmed")
                        .confirmedAt(LocalDateTime.of(2026, 11, 13, 10, 0))
                        .build()));
        when(cohortRepository.findById(cohortId))
                .thenReturn(Optional.of(TrainingCohort.builder()
                        .id(cohortId).companyId(companyId).planId(planId).name("新人训练营").build()));
        when(planRepository.findById(planId))
                .thenReturn(Optional.of(TrainingPlan.builder()
                        .id(planId).companyId(companyId).name("销售新人训练营").build()));
        when(userRepository.findById(learnerId))
                .thenReturn(Optional.of(User.builder().id(learnerId).name("张三").build()));

        ResponseEntity<String> res = controller.certificate(assignmentId);

        assertEquals(200, res.getStatusCode().value());
        assertTrue(res.getHeaders().getContentType().toString().startsWith("text/html"));
        String body = res.getBody();
        assertTrue(body != null && body.contains("张三"));
        assertTrue(body != null && body.contains("销售新人训练营"));
        assertTrue(body != null && body.contains("2026-11-13"));
        assertTrue(body != null && body.contains("MF-"));
    }
}
