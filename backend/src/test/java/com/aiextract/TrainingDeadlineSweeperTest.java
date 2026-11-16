package com.aiextract;

import com.aiextract.repository.TrainingAttemptRepository;
import com.aiextract.scheduler.TrainingDeadlineSweeper;
import com.aiextract.service.TrainingDeadlineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TrainingDeadlineSweeper 单元测试 —— 两类逾期来源、单条异常隔离、开关。
 */
@ExtendWith(MockitoExtension.class)
class TrainingDeadlineSweeperTest {

    @Mock private TrainingAttemptRepository attemptRepository;
    @Mock private TrainingDeadlineService deadlineService;

    private TrainingDeadlineSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new TrainingDeadlineSweeper(attemptRepository, deadlineService);
        ReflectionTestUtils.setField(sweeper, "enabled", true);
        ReflectionTestUtils.setField(sweeper, "batchSize", 50);
    }

    @Test
    void tick_processesExamAndPracticeOverdueIds() {
        UUID exam = UUID.randomUUID();
        UUID practice = UUID.randomUUID();
        when(attemptRepository.findOverdueIds(50)).thenReturn(List.of(exam));
        when(attemptRepository.findOverduePracticeIds(50)).thenReturn(List.of(practice));

        sweeper.tick();

        verify(deadlineService).handleOverdue(exam);
        verify(deadlineService).handleOverdue(practice);
    }

    @Test
    void tick_singleFailureDoesNotBlockOthers() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(attemptRepository.findOverdueIds(50)).thenReturn(List.of(first, second));
        when(attemptRepository.findOverduePracticeIds(50)).thenReturn(List.of());
        doThrow(new IllegalStateException("boom")).when(deadlineService).handleOverdue(first);

        sweeper.tick();

        verify(deadlineService).handleOverdue(second);
    }

    @Test
    void tick_disabled_doesNotQuery() {
        ReflectionTestUtils.setField(sweeper, "enabled", false);

        sweeper.tick();

        verifyNoInteractions(attemptRepository, deadlineService);
    }
}
