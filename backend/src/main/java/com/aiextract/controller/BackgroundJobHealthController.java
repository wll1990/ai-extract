package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.service.BackgroundJobHealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 后台任务健康检查（只读）—— 运维告警与值班页的数据源。
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
@RestController
@RequestMapping("/admin/health")
@RequiredArgsConstructor
public class BackgroundJobHealthController {

    private final BackgroundJobHealthService service;

    @GetMapping("/background-jobs")
    public ApiResponse<Map<String, Object>> backgroundJobs(
            @RequestParam(defaultValue = "24") int hours) {
        return ApiResponse.success(service.snapshot(hours));
    }
}
