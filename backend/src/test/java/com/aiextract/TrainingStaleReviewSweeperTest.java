package com.aiextract;

import com.aiextract.model.TrainingAssignment;
import com.aiextract.model.TrainingAttempt;
import com.aiextract.model.UserNotification;
import com.aiextract.repository.TrainingAssignmentRepository;
import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.scheduler.TrainingStaleReviewSweeper;
import com.aiextract.service.TrainingNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 待复核滞留提醒：只提醒、不自动定稿；同一天去重。
 */
@ExtendWith(MockitoExtension.class)
class TrainingStaleReviewSweeperTest {

    @Mock private TrainingAttemptRepository attemptRepository;
    @Mock private TrainingAssignmentRepository assignmentRepository;
    @Mock private TrainingNotificationService notificationService;

    private TrainingStaleReviewSweeper sweeper;
    private final UUID attemptId = UUID.randomUUID();
    private final UUID assignmentId = UUID.randomUUID();
    private final UUID cohortId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        sweeper = new TrainingStaleReviewSweeper(attemptRepository, assignmentRepository, notificationService);
        ReflectionTestUtils.setField(sweeper, "enabled", true);
        ReflectionTestUtils.setField(sweeper, "thresholdHours", 24L);
        ReflectionTestUtils.setField(sweeper, "batchSize", 50);
    }

    @Test
    void staleAttempt_notifiesReviewerWithDailyDedupe() {
        when(attemptRepository.findStaleReviewIds(any(), eq(50), anyInt())).thenReturn(List.of(attemptId));
        when(attemptRepository.findById(attemptId)).thenReturn(Optional.of(
                TrainingAttempt.builder().id(attemptId).assignmentId(assignmentId)
                        .attemptNo(1).status(TrainingAttempt.STATUS_PENDING_REVIEW)
                        .submittedAt(LocalDateTime.now().minusDays(2)).build()));
        when(assignmentRepository.findById(assignmentId)).thenReturn(Optional.of(
                TrainingAssignment.builder().id(assignmentId).cohortId(cohortId)
                        .companyId(companyId).build()));

        sweeper.sweep();

        // 提醒按批次聚合，同一批次同一天只发一条
        verify(notificationService).notifyReviewerByCohortId(eq(cohortId),
                eq(UserNotification.TYPE_STALE_REVIEW), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.contains("training:stale-review:" + cohortId));
    }

    @Test
    void disabled_doesNothing() {
        ReflectionTestUtils.setField(sweeper, "enabled", false);

        sweeper.sweep();

        verify(attemptRepository, never()).findStaleReviewIds(any(), anyInt(), anyInt());
    }

    @Test
    void oneBrokenAttempt_doesNotStopOthers() {
        UUID second = UUID.randomUUID();
        when(attemptRepository.findStaleReviewIds(any(), eq(50), anyInt())).thenReturn(List.of(attemptId, second));
        when(attemptRepository.findById(attemptId)).thenThrow(new RuntimeException("boom"));
        when(attemptRepository.findById(second)).thenReturn(Optional.empty());

        sweeper.sweep();

        verify(attemptRepository).findById(second);
    }
}
