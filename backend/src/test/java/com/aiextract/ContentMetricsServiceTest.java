package com.aiextract;

import com.aiextract.repository.ContentMetricsRepository;
import com.aiextract.service.ContentMetricsService;
import com.aiextract.service.TrainingAccessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 采纳指标：通过率不含待审，平均审核时长按已处理条目加权。 */
@ExtendWith(MockitoExtension.class)
class ContentMetricsServiceTest {

    @Mock private ContentMetricsRepository metricsRepository;
    @Mock private TrainingAccessService accessService;

    private final UUID companyId = UUID.randomUUID();

    @Test
    void adoption_aggregatesAndExcludesPendingFromRate() {
        when(accessService.requireCompanyId()).thenReturn(companyId);
        when(metricsRepository.adoptionRows(eq(companyId), any(LocalDateTime.class))).thenReturn(List.of(
                new Object[]{"knowledge", 10L, 4L, 2L, 7200.0},
                new Object[]{"case", 2L, 1L, 1L, null},
                new Object[]{"eval_item", 3L, 0L, 0L, 3600.0}
        ));

        var adoption = new ContentMetricsService(metricsRepository, accessService).adoption();

        assertEquals(15, adoption.created());
        assertEquals(5, adoption.approved());
        assertEquals(3, adoption.discarded());
        // 10-4-2 + 2-1-1 + 3-0-0 = 7
        assertEquals(7, adoption.pending());
        assertEquals(0.625, adoption.approvalRate());
        assertEquals(2.0, adoption.avgReviewHours());
        assertEquals(3, adoption.byKind().size());
    }
}
