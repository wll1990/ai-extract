package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.service.TrainingWorkbenchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 导师工作台（TR-07）：待复核 / 我带的批次 / 逾期学员。
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
@RestController
@RequestMapping("/admin/training/workbench")
@RequiredArgsConstructor
public class TrainingWorkbenchController {

    private final TrainingWorkbenchService service;

    @GetMapping
    public ApiResponse<Map<String, Object>> workbench() {
        return ApiResponse.success(service.workbench());
    }
}
