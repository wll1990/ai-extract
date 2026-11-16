package com.aiextract;

import com.aiextract.config.TokenContext;
import com.aiextract.model.User;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.repository.TrainingCertificationRepository;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.repository.UserRepository;
import com.aiextract.service.TrainingWorkbenchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导师工作台数据范围（TR-07）：导师只看自己带的批次，管理员看全企业。
 */
@ExtendWith(MockitoExtension.class)
class TrainingWorkbenchServiceTest {

    @Mock private TrainingCohortRepository cohortRepository;
    @Mock private TrainingAssignmentRepository assignmentRepository;
    @Mock private TrainingAttemptRepository attemptRepository;
    @Mock private TrainingTaskRepository taskRepository;
    @Mock private TrainingCertificationRepository certificationRepository;
    @Mock private UserRepository userRepository;

    private TrainingWorkbenchService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new TrainingWorkbenchService(cohortRepository, assignmentRepository,
                attemptRepository, taskRepository, certificationRepository, userRepository);
        TokenContext.set(userId, companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void mentorOnlyLoadsOwnCohorts() {
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(User.builder().id(userId).role("employee").build()));
        when(cohortRepository.findByCompanyIdAndMentorIdOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        Map<String, Object> result = service.workbench();

        verify(cohortRepository, never()).findByCompanyIdOrderByCreatedAtDesc(any());
        assertTrue(((List<?>) result.get("pendingReviews")).isEmpty());
    }

    @Test
    void adminSeesAllCompanyCohorts() {
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(User.builder().id(userId).role("company_admin").build()));
        when(cohortRepository.findByCompanyIdOrderByCreatedAtDesc(companyId)).thenReturn(List.of());

        Map<String, Object> result = service.workbench();

        verify(cohortRepository).findByCompanyIdOrderByCreatedAtDesc(companyId);
        assertEquals(0, ((Map<?, ?>) result.get("counts")).get("pendingReview"));
    }
}
