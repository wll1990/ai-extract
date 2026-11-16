package com.aiextract.service;

import com.aiextract.repository.ContentMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AI 草稿采纳率与审核效率（近 30 天）。
 *
 * <p>通过率 = approved / (approved + discarded)；尚未审核的草稿单列 pending，
 * 不把「还没人看」算成失败。</p>
 */
@Service
@RequiredArgsConstructor
public class ContentMetricsService {

    private static final int WINDOW_DAYS = 30;

    private final ContentMetricsRepository metricsRepository;
    private final TrainingAccessService accessService;

    public record KindMetrics(String kind, long created, long approved, long discarded,
                              long pending, double approvalRate, double avgReviewHours) {
    }

    public record Adoption(int windowDays, long created, long approved, long discarded,
                           long pending, double approvalRate, double avgReviewHours,
                           Map<String, KindMetrics> byKind) {
    }

    public Adoption adoption() {
        UUID companyId = accessService.requireCompanyId();
        LocalDateTime since = LocalDateTime.now().minusDays(WINDOW_DAYS);
        Map<String, KindMetrics> byKind = new LinkedHashMap<>();
        long created = 0;
        long approved = 0;
        long discarded = 0;
        double latencySum = 0;
        long latencyCount = 0;

        for (Object[] row : metricsRepository.adoptionRows(companyId, since)) {
            String kind = String.valueOf(row[0]);
            long c = num(row[1]);
            long a = num(row[2]);
            long d = num(row[3]);
            double avgSeconds = row[4] == null ? 0 : ((Number) row[4]).doubleValue();
            long pending = Math.max(0, c - a - d);
            double rate = a + d == 0 ? 0 : round3((double) a / (a + d));
            byKind.put(kind, new KindMetrics(kind, c, a, d, pending, rate,
                    round3(avgSeconds / 3600)));
            created += c;
            approved += a;
            discarded += d;
            if (avgSeconds > 0) {
                latencySum += avgSeconds * (a + d);
                latencyCount += a + d;
            }
        }
        double overallRate = approved + discarded == 0 ? 0
                : round3((double) approved / (approved + discarded));
        double avgHours = latencyCount == 0 ? 0 : round3(latencySum / latencyCount / 3600);
        return new Adoption(WINDOW_DAYS, created, approved, discarded,
                Math.max(0, created - approved - discarded), overallRate, avgHours, byKind);
    }

    private long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
