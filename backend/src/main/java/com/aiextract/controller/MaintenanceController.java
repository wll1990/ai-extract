package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.service.DashScopeEmbeddingService;
import com.aiextract.service.ReportGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 维护接口 — 数据回填、报告修复。
 * Controller 只做路由，业务逻辑在 Service。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MaintenanceController {

    private final ExperienceGrainRepository grainRepository;
    private final DashScopeEmbeddingService embeddingService;
    private final ReportGenerationService reportGenerationService;

    @PostMapping("/admin/maintenance/backfill-embeddings")
    public ApiResponse<Map<String, Object>> backfillEmbeddings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var grainPage = grainRepository.findWithoutEmbedding(PageRequest.of(page, size));
        var grains = grainPage.getContent();
        if (grains.isEmpty()) {
            return ApiResponse.success(Map.of(
                "success", 0, "fail", 0,
                "page", page, "totalPages", grainPage.getTotalPages(), "totalElements", grainPage.getTotalElements()));
        }

        int[] results;
        try {
            results = embeddingService.backfillEmbeddings(grains);
        } catch (Exception e) {
            log.error("批量嵌入失败: {}", e.getMessage());
            return ApiResponse.success(Map.of(
                "success", 0, "fail", grains.size(), "error", e.getMessage(),
                "page", page, "totalPages", grainPage.getTotalPages()));
        }

        int success = 0, fail = 0;
        for (int r : results) {
            if (r > 0) success++; else fail++;
        }

        log.info("embedding回填 page={} success={} fail={}", page, success);
        return ApiResponse.success(Map.of(
            "success", success, "fail", fail,
            "page", page, "totalPages", grainPage.getTotalPages(), "totalElements", grainPage.getTotalElements()));
    }

    @PostMapping("/admin/maintenance/backfill-report")
    public ApiResponse<Map<String, Object>> backfillReport(@RequestParam UUID skillId) {
        reportGenerationService.generateAsync(skillId);
        return ApiResponse.success(Map.of("message", "报告生成已触发，请稍后查看"));
    }
}
