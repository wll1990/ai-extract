package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.service.TrainingPickerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 培训选择器 —— 计划编辑器/批次分配统一搜索入口。
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@RestController
@RequestMapping("/admin/training/pickers")
@RequiredArgsConstructor
public class AdminTrainingPickerController {

    private final TrainingPickerService pickerService;

    @GetMapping("/{kind}")
    public ApiResponse<Map<String, Object>> pick(
            @PathVariable String kind,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String coach,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<TrainingPickerService.PickerItem> result =
                pickerService.pick(kind, keyword, status, role, "true".equals(coach), page, size);
        List<Map<String, Object>> content = result.getContent().stream().map(item -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", item.id());
            row.put("name", item.name());
            row.put("subtitle", item.subtitle());
            row.put("status", item.status());
            return row;
        }).toList();
        return ApiResponse.success(PageResponse.of(content, result, page, size));
    }
}
