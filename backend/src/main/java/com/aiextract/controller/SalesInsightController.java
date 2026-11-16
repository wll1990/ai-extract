package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.SalesCommonIssueView;
import com.aiextract.service.SalesCommonIssueService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 团队共性问题：跨复盘聚合，供管理者发现反复出现的问题并生成培训主题。
 */
@RestController
@RequestMapping("/sales/insights")
@RequiredArgsConstructor
public class SalesInsightController {

    private final SalesCommonIssueService service;

    @GetMapping("/common-issues")
    public ApiResponse<SalesCommonIssueView> commonIssues(
            @RequestParam(defaultValue = "90") int days) {
        return ApiResponse.success(service.commonIssues(days));
    }

    /** 把一类共性问题沉淀为知识草稿（pending），用于后续培训/检索 */
    @PostMapping("/common-issues/{key}/knowledge")
    public ApiResponse<Map<String, Object>> createKnowledge(
            @PathVariable String key,
            @RequestParam(defaultValue = "90") int days) {
        return ApiResponse.success(service.createTrainingKnowledge(key, days));
    }
}
