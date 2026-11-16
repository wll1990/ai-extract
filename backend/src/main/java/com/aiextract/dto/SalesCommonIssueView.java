package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 团队共性问题聚合结果：把一段时间内已生成报告的复盘按问题类别归并，
 * 用于管理者看清「反复出现的是什么问题」，并追溯到具体复盘。
 */
public record SalesCommonIssueView(
        int periodDays,
        int reportCount,
        List<Issue> issues
) {
    public record Issue(
            String key,
            String label,
            int reportCount,
            int pointCount,
            List<Sample> samples,
            List<ReviewRef> reviews
    ) {
    }

    public record Sample(
            UUID sessionId,
            String customerName,
            String point,
            String suggestion
    ) {
    }

    public record ReviewRef(
            UUID sessionId,
            String customerName,
            LocalDateTime createdAt
    ) {
    }
}
