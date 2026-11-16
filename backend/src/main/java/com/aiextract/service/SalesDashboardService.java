package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ReviewSession;
import com.aiextract.repository.FollowUpTaskRepository;
import com.aiextract.repository.LeadRepository;
import com.aiextract.repository.ReviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 销售个人看板（SA-07）—— 只统计当前登录人自己的复盘、线索与跟进任务。
 *
 * <p>口径写死在服务里并在前端展示：
 * 本周=自然周（周一起）；待跟进=跟进中且 7 天无活动或已到下次跟进时间；
 * 逾期任务=未完成且 due_at 已过。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
@Service
@RequiredArgsConstructor
public class SalesDashboardService {

    private static final int WEEKS = 8;
    private static final int STALE_DAYS = 7;
    private static final int RECENT_REVIEWS = 5;

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_FOLLOWING = "following";
    private static final String STATUS_CONVERTED = "converted";
    private static final String STATUS_NEW = "new";
    private static final String STATUS_INVALID = "invalid";

    private final ReviewSessionRepository reviewSessionRepository;
    private final LeadRepository leadRepository;
    private final FollowUpTaskRepository followUpTaskRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> mine() {
        UUID companyId = TokenContext.getCompanyId();
        UUID userId = TokenContext.get();
        if (companyId == null || userId == null) {
            throw new BusinessException(403, "无企业归属，不能查看个人看板");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        LocalDateTime weekStart = today.with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime staleBefore = now.minusDays(STALE_DAYS);
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = today.plusDays(1).atStartOfDay();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("range", Map.of(
                "today", today.toString(),
                "weekStart", weekStart.toLocalDate().toString(),
                "monthStart", monthStart.toLocalDate().toString()));

        // 复盘
        Map<String, Object> reviews = new LinkedHashMap<>();
        reviews.put("thisWeek", reviewSessionRepository
                .countByCompanyIdAndReviewerIdAndCreatedAtAfter(companyId, userId, weekStart));
        reviews.put("thisMonth", reviewSessionRepository
                .countByCompanyIdAndReviewerIdAndCreatedAtAfter(companyId, userId, monthStart));
        reviews.put("total", reviewSessionRepository
                .countByCompanyIdAndReviewerId(companyId, userId));
        reviews.put("reportReady", reviewSessionRepository
                .countByCompanyIdAndReviewerIdAndStatus(companyId, userId, "report_ready"));
        reviews.put("converted", reviewSessionRepository
                .countByCompanyIdAndReviewerIdAndStatus(companyId, userId, "converted"));
        result.put("reviews", reviews);

        // 近 8 周复盘趋势（缺口补 0）
        result.put("weekly", weeklyTrend(companyId, userId, today));

        // 线索
        Map<String, Object> leads = new LinkedHashMap<>();
        leads.put("total", leadRepository.countByCompanyIdAndOwnerId(companyId, userId));
        leads.put("new", leadRepository
                .countByCompanyIdAndOwnerIdAndStatus(companyId, userId, STATUS_NEW));
        leads.put("following", leadRepository
                .countByCompanyIdAndOwnerIdAndStatus(companyId, userId, STATUS_FOLLOWING));
        leads.put("converted", leadRepository
                .countByCompanyIdAndOwnerIdAndStatus(companyId, userId, STATUS_CONVERTED));
        leads.put("invalid", leadRepository
                .countByCompanyIdAndOwnerIdAndStatus(companyId, userId, STATUS_INVALID));
        leads.put("newThisWeek", leadRepository
                .countByCompanyIdAndOwnerIdAndCreatedAtAfter(companyId, userId, weekStart));
        leads.put("convertedThisMonth", leadRepository
                .countByCompanyIdAndOwnerIdAndStatusAndCreatedAtAfter(
                        companyId, userId, STATUS_CONVERTED, monthStart));
        leads.put("needFollowUp", leadRepository
                .countNeedFollowUp(companyId, userId, staleBefore, now));
        leads.put("overdueFollowUp", leadRepository
                .countOverdueFollowUp(companyId, userId, now));
        result.put("leads", leads);

        // 跟进任务
        Map<String, Object> tasks = new LinkedHashMap<>();
        tasks.put("pending", followUpTaskRepository
                .countByCompanyIdAndOwnerIdAndStatus(companyId, userId, STATUS_PENDING));
        tasks.put("overdue", followUpTaskRepository
                .countByCompanyIdAndOwnerIdAndStatusAndDueAtBefore(
                        companyId, userId, STATUS_PENDING, now));
        tasks.put("dueToday", followUpTaskRepository
                .countByCompanyIdAndOwnerIdAndStatusAndDueAtBetween(
                        companyId, userId, STATUS_PENDING, dayStart, dayEnd));
        tasks.put("doneThisWeek", followUpTaskRepository
                .countByCompanyIdAndOwnerIdAndStatusAndCompletedAtAfter(
                        companyId, userId, "done", weekStart));
        result.put("tasks", tasks);

        // 最近复盘
        List<Map<String, Object>> recent = new ArrayList<>();
        for (ReviewSession session : reviewSessionRepository
                .findByCompanyIdAndReviewerIdOrderByCreatedAtDesc(
                        companyId, userId, PageRequest.of(0, RECENT_REVIEWS))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", session.getId().toString());
            item.put("customerName", session.getCustomerName());
            item.put("topic", session.getTopic());
            item.put("status", session.getStatus());
            item.put("coachName", session.getCoachName());
            item.put("createdAt", session.getCreatedAt() != null
                    ? session.getCreatedAt().toString() : null);
            recent.add(item);
        }
        result.put("recentReviews", recent);

        return result;
    }

    private List<Map<String, Object>> weeklyTrend(UUID companyId, UUID userId, LocalDate today) {
        LocalDate firstWeek = today.with(DayOfWeek.MONDAY).minusWeeks(WEEKS - 1L);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : reviewSessionRepository.countWeeklyByReviewer(
                companyId, userId, firstWeek.atStartOfDay())) {
            counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        List<Map<String, Object>> series = new ArrayList<>(WEEKS);
        for (int i = 0; i < WEEKS; i++) {
            String week = firstWeek.plusWeeks(i).toString();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("week", week);
            point.put("count", counts.getOrDefault(week, 0L));
            series.add(point);
        }
        return series;
    }
}
