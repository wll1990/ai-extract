package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.config.TokenContext;
import com.aiextract.model.TokenUsageLog;
import com.aiextract.repository.TokenUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 管理端 Token 用量统计接口
 *
 * @author AI Extract Team
 * @since 2026-07-24
 */
@Slf4j
@RestController
@RequestMapping("/admin/token-usage")
@RequiredArgsConstructor
public class AdminTokenUsageController {

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 365;
    private static final int MIN_SIZE = 5;
    private static final int MAX_SIZE = 100;

    private final TokenUsageLogRepository repository;
    private final CompanyScopeService companyScopeService;
    private final com.aiextract.repository.UserRepository userRepository;

    /** 汇总卡片：今日 / 本月 / 总计 */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        UUID companyId = TokenContext.getCompanyId();
        List<UUID> userIds = companyScopeService.getUserIds(companyId);

        List<Object[]> todayList = userIds != null
                ? repository.sumByDateAndUserIdIn(today, userIds)
                : repository.sumByDate(today);
        Object[] todaySum = todayList.isEmpty() ? new Object[]{0L, 0L, 0L} : todayList.get(0);
        long todayInput = ((Number) todaySum[0]).longValue();
        long todayOutput = ((Number) todaySum[1]).longValue();
        long todayCount = ((Number) todaySum[2]).longValue();

        // 本月：从月初到今天逐日聚合
        List<Object[]> monthRows = userIds != null
                ? repository.sumByDateSinceAndUserIdIn(monthStart, userIds)
                : repository.sumByDateSince(monthStart);
        long monthInput = monthRows.stream().mapToLong(r -> ((Number) r[1]).longValue()).sum();
        long monthOutput = monthRows.stream().mapToLong(r -> ((Number) r[2]).longValue()).sum();
        long monthCount = monthRows.stream().mapToLong(r -> ((Number) r[3]).longValue()).sum();

        // 总计：单次 SUM 聚合，无需 GROUP BY
        List<Object[]> totalList = userIds != null
                ? repository.sumTotalByUserIdIn(userIds)
                : repository.sumTotal();
        Object[] totalSum = totalList.isEmpty() ? new Object[]{0L, 0L, 0L} : totalList.get(0);
        long totalInput = ((Number) totalSum[0]).longValue();
        long totalOutput = ((Number) totalSum[1]).longValue();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("today", Map.of("inputTokens", todayInput, "outputTokens", todayOutput, "count", todayCount));
        result.put("month", Map.of("inputTokens", monthInput, "outputTokens", monthOutput, "count", monthCount));
        result.put("total", Map.of("inputTokens", totalInput, "outputTokens", totalOutput));
        return ApiResponse.success(result);
    }

    /** 按天趋势（最近 N 天，上限 365） */
    @GetMapping("/daily")
    public ApiResponse<List<Map<String, Object>>> daily(@RequestParam(defaultValue = "30") int days) {
        int clampedDays = Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
        LocalDate since = LocalDate.now().minusDays(clampedDays);
        UUID companyId = TokenContext.getCompanyId();
        List<UUID> userIds = companyScopeService.getUserIds(companyId);
        List<Object[]> rows = userIds != null
                ? repository.sumByDateSinceAndUserIdIn(since, userIds)
                : repository.sumByDateSince(since);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", row[0].toString());
            item.put("inputTokens", ((Number) row[1]).longValue());
            item.put("outputTokens", ((Number) row[2]).longValue());
            item.put("count", ((Number) row[3]).longValue());
            result.add(item);
        }
        return ApiResponse.success(result);
    }

    /** 分页明细（page≥0, 5≤size≤100） */
    @GetMapping("/logs")
    public ApiResponse<Map<String, Object>> logs(@RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        int clampedPage = Math.max(0, page);
        int clampedSize = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        UUID companyId = TokenContext.getCompanyId();
        List<UUID> userIds = companyScopeService.getUserIds(companyId);

        List<TokenUsageLog> list = userIds != null
                ? repository.findRecentByUserIdIn(userIds, PageRequest.of(clampedPage, clampedSize))
                : repository.findRecent(PageRequest.of(clampedPage, clampedSize));
        long total = userIds != null
                ? repository.countByUserIdIn(userIds)
                : repository.countAll();

        // 批量查用户名
        List<UUID> distinctUserIds = list.stream()
                .map(TokenUsageLog::getUserId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, String> userNames = distinctUserIds.isEmpty() ? Map.of()
                : userRepository.findAllById(distinctUserIds).stream()
                    .collect(java.util.stream.Collectors.toMap(
                        com.aiextract.model.User::getId, com.aiextract.model.User::getName, (a, b) -> a));

        List<Map<String, Object>> items = new ArrayList<>();
        for (TokenUsageLog t : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId().toString());
            m.put("userId", t.getUserId() != null ? t.getUserId().toString() : null);
            m.put("userName", t.getUserId() != null ? userNames.getOrDefault(t.getUserId(), "系统/调度") : "系统/调度");
            m.put("usageDate", t.getUsageDate().toString());
            m.put("modelType", t.getModelType());
            m.put("modelName", t.getModelName());
            m.put("inputTokens", t.getInputTokens());
            m.put("outputTokens", t.getOutputTokens());
            m.put("promptChars", t.getPromptChars());
            m.put("completionChars", t.getCompletionChars());
            m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
            items.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("page", clampedPage);
        result.put("size", clampedSize);
        result.put("total", total);
        return ApiResponse.success(result);
    }
}
