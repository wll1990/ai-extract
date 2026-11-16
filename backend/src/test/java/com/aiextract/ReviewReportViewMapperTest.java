package com.aiextract;

import com.aiextract.dto.ReviewReportView;
import com.aiextract.dto.ReviewReportViewMapper;
import com.aiextract.model.ReviewReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 报告等待体验（SA-02）：queuedAt/startedAt 必须随元数据下发。
 */
class ReviewReportViewMapperTest {

    @Test
    void metaViewExposesQueuedAndStartedTimestamps() {
        LocalDateTime created = LocalDateTime.of(2026, 11, 13, 10, 0);
        LocalDateTime started = LocalDateTime.of(2026, 11, 13, 10, 1);
        ReviewReport report = ReviewReport.builder()
                .id(UUID.randomUUID()).status("generating")
                .createdAt(created).startedAt(started)
                .build();

        ReviewReportView view = ReviewReportViewMapper.toMetaView(report);

        assertEquals(created, view.queuedAt());
        assertEquals(started, view.startedAt());
        assertNull(view.payload());
    }

    @Test
    void queuedReportHasNullStartedAt() {
        ReviewReport report = ReviewReport.builder()
                .id(UUID.randomUUID()).status("queued")
                .createdAt(LocalDateTime.now())
                .build();

        ReviewReportView view = ReviewReportViewMapper.toView(report, new ObjectMapper());

        assertNull(view.startedAt());
    }
}
