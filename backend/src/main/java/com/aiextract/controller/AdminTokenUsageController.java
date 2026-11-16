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
    private final com.aiextract.repository.CompanyRepository companyRepository;

    /** 汇总卡片：今日 / 本月 / 总计 */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        UUID companyId = TokenContext.getCompanyId();
        boolean unrestricted = companyScopeService.isUnrestrictedDataScope();
        if (!unrestricted && companyId == null) {
            return ApiResponse.success(emptySummary());
        }

        List<Object[]> todayList = unrestricted
                ? repository.sumByDate(today)
                : repository.sumByDateAndCompanyId(today, companyId);
        Object[] todaySum = todayList.isEmpty() ? new Object[]{0L, 0L, 0L} : todayList.get(0);
        long todayInput = ((Number) todaySum[0]).longValue();
        long todayOutput = ((Number) todaySum[1]).longValue();
        long todayCount = ((Number) todaySum[2]).longValue();

        // 本月：从月初到今天逐日聚合
        List<Object[]> monthRows = unrestricted
                ? repository.sumByDateSince(monthStart)
                : repository.sumByDateSinceAndCompanyId(monthStart, companyId);
        long monthInput = monthRows.stream().mapToLong(r -> ((Number) r[1]).longValue()).sum();
        long monthOutput = monthRows.stream().mapToLong(r -> ((Number) r[2]).longValue()).sum();
        long monthCount = monthRows.stream().mapToLong(r -> ((Number) r[3]).longValue()).sum();

        // 总计：单次 SUM 聚合，无需 GROUP BY
        List<Object[]> totalList = unrestricted
                ? repository.sumTotal()
                : repository.sumTotalByCompanyId(companyId);
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
        // 含今天共 N 天：今天 - (N-1)，否则「近 7 天」实际取 8 天
        LocalDate since = LocalDate.now().minusDays(clampedDays - 1L);
        UUID companyId = TokenContext.getCompanyId();
        boolean unrestricted = companyScopeService.isUnrestrictedDataScope();
        if (!unrestricted && companyId == null) {
            return ApiResponse.success(List.of());
        }
        List<Object[]> rows = unrestricted
                ? repository.sumByDateSince(since)
                : repository.sumByDateSinceAndCompanyId(since, companyId);
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

    /** 按模型分布（最近 N 天）：用于「谁在消耗 token」的直观占比图 */
    @GetMapping("/by-model")
    public ApiResponse<List<Map<String, Object>>> byModel(@RequestParam(defaultValue = "30") int days) {
        int clampedDays = Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
        LocalDate since = LocalDate.now().minusDays(clampedDays - 1L);
        UUID companyId = TokenContext.getCompanyId();
        boolean unrestricted = companyScopeService.isUnrestrictedDataScope();
        if (!unrestricted && companyId == null) {
            return ApiResponse.success(List.of());
        }
        List<Object[]> rows = unrestricted
                ? repository.sumByModelSince(since)
                : repository.sumByModelSinceAndCompanyId(since, companyId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("model", row[0]);
            item.put("inputTokens", ((Number) row[1]).longValue());
            item.put("outputTokens", ((Number) row[2]).longValue());
            item.put("count", ((Number) row[3]).longValue());
            result.add(item);
        }
        return ApiResponse.success(result);
    }

    /** 企业用量排行（仅超管；按所选时间窗取前 5） */
    @GetMapping("/by-company")
    public ApiResponse<List<Map<String, Object>>> byCompany(@RequestParam(defaultValue = "30") int days) {
        int clampedDays = Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
        LocalDate since = LocalDate.now().minusDays(clampedDays - 1L);
        if (!companyScopeService.isUnrestrictedDataScope()) {
            return ApiResponse.success(List.of());
        }
        List<Object[]> rows = repository.topCompaniesSince(since, PageRequest.of(0, 5));
        List<UUID> ids = rows.stream().map(r -> (UUID) r[0]).toList();
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            companyRepository.findAllById(ids)
                    .forEach(c -> names.put(c.getId(), c.getName()));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : rows) {
            UUID id = (UUID) row[0];
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("companyId", id.toString());
            item.put("companyName", names.getOrDefault(id, id.toString().substring(0, 8)));
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
        boolean unrestricted = companyScopeService.isUnrestrictedDataScope();
        if (!unrestricted && companyId == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("items", List.of());
            empty.put("page", clampedPage);
            empty.put("size", clampedSize);
            empty.put("total", 0L);
            return ApiResponse.success(empty);
        }

        List<TokenUsageLog> list = unrestricted
                ? repository.findRecent(PageRequest.of(clampedPage, clampedSize))
                : repository.findRecentByCompanyId(companyId, PageRequest.of(clampedPage, clampedSize));
        long total = unrestricted
                ? repository.countAll()
                : repository.countByCompanyId(companyId);

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

    private Map<String, Object> emptySummary() {
        Map<String, Object> zero = Map.of("inputTokens", 0L, "outputTokens", 0L, "count", 0L);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("today", zero);
        result.put("month", zero);
        result.put("total", Map.of("inputTokens", 0L, "outputTokens", 0L));
        return result;
    }
}
