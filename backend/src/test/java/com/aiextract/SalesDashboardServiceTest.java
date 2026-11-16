package com.aiextract;

import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.repository.FollowUpTaskRepository;
import com.aiextract.repository.LeadRepository;
import com.aiextract.repository.ReviewSessionRepository;
import com.aiextract.service.SalesDashboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 销售个人看板：只统计本人数据，近 8 周缺口补 0。
 */
@ExtendWith(MockitoExtension.class)
class SalesDashboardServiceTest {

    @Mock
    private ReviewSessionRepository reviewSessionRepository;
    @Mock
    private LeadRepository leadRepository;
    @Mock
    private FollowUpTaskRepository followUpTaskRepository;

    private SalesDashboardService service;
    private final UUID companyId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new SalesDashboardService(reviewSessionRepository, leadRepository, followUpTaskRepository);
        TokenContext.set(userId, companyId);
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mine_returnsOwnCountsAndFilledWeeklySeries() {
        when(reviewSessionRepository.countByCompanyIdAndReviewerIdAndCreatedAtAfter(
                eq(companyId), eq(userId), any())).thenReturn(3L, 7L);
        when(reviewSessionRepository.countByCompanyIdAndReviewerId(companyId, userId)).thenReturn(20L);
        when(reviewSessionRepository.countByCompanyIdAndReviewerIdAndStatus(
                companyId, userId, "report_ready")).thenReturn(5L);
        when(reviewSessionRepository.countByCompanyIdAndReviewerIdAndStatus(
                companyId, userId, "converted")).thenReturn(2L);
        when(reviewSessionRepository.countWeeklyByReviewer(eq(companyId), eq(userId), any()))
                .thenReturn(List.of());
        when(reviewSessionRepository.findByCompanyIdAndReviewerIdOrderByCreatedAtDesc(
                eq(companyId), eq(userId), any(Pageable.class))).thenReturn(Page.empty());

        when(leadRepository.countByCompanyIdAndOwnerId(companyId, userId)).thenReturn(10L);
        when(leadRepository.countByCompanyIdAndOwnerIdAndStatus(companyId, userId, "new")).thenReturn(2L);
        when(leadRepository.countByCompanyIdAndOwnerIdAndStatus(companyId, userId, "following")).thenReturn(6L);
        when(leadRepository.countByCompanyIdAndOwnerIdAndStatus(companyId, userId, "converted")).thenReturn(1L);
        when(leadRepository.countByCompanyIdAndOwnerIdAndStatus(companyId, userId, "invalid")).thenReturn(1L);
        when(leadRepository.countByCompanyIdAndOwnerIdAndCreatedAtAfter(eq(companyId), eq(userId), any()))
                .thenReturn(4L);
        when(leadRepository.countByCompanyIdAndOwnerIdAndStatusAndCreatedAtAfter(
                eq(companyId), eq(userId), eq("converted"), any())).thenReturn(1L);
        when(leadRepository.countNeedFollowUp(eq(companyId), eq(userId), any(), any())).thenReturn(2L);
        when(leadRepository.countOverdueFollowUp(eq(companyId), eq(userId), any())).thenReturn(1L);

        when(followUpTaskRepository.countByCompanyIdAndOwnerIdAndStatus(
                companyId, userId, "pending")).thenReturn(4L);
        when(followUpTaskRepository.countByCompanyIdAndOwnerIdAndStatusAndDueAtBefore(
                eq(companyId), eq(userId), eq("pending"), any())).thenReturn(1L);
        when(followUpTaskRepository.countByCompanyIdAndOwnerIdAndStatusAndDueAtBetween(
                eq(companyId), eq(userId), eq("pending"), any(), any())).thenReturn(2L);
        when(followUpTaskRepository.countByCompanyIdAndOwnerIdAndStatusAndCompletedAtAfter(
                eq(companyId), eq(userId), eq("done"), any())).thenReturn(3L);

        Map<String, Object> result = service.mine();

        Map<String, Object> reviews = (Map<String, Object>) result.get("reviews");
        assertEquals(3L, reviews.get("thisWeek"));
        assertEquals(7L, reviews.get("thisMonth"));

        Map<String, Object> leads = (Map<String, Object>) result.get("leads");
        assertEquals(2L, leads.get("needFollowUp"));
        assertEquals(1L, leads.get("overdueFollowUp"));

        Map<String, Object> tasks = (Map<String, Object>) result.get("tasks");
        assertEquals(4L, tasks.get("pending"));
        assertEquals(1L, tasks.get("overdue"));

        List<Map<String, Object>> weekly = (List<Map<String, Object>>) result.get("weekly");
        assertEquals(8, weekly.size(), "近 8 周必须补齐，无数据补 0");
        assertEquals(0L, weekly.get(0).get("count"));
    }

    @Test
    void mine_withoutCompany_isForbidden() {
        TokenContext.clear();
        BusinessException e = assertThrows(BusinessException.class, () -> service.mine());
        assertEquals(403, e.getErrorCode());
    }
}
