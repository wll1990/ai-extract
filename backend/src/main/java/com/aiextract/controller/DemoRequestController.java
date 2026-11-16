package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.dto.DemoRequestCreate;
import com.aiextract.dto.DemoRequestStatusUpdate;
import com.aiextract.model.DemoRequest;
import com.aiextract.service.DemoRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 门户「预约演示」：公开提交 + 运营查询。
 */
@RestController
@RequiredArgsConstructor
public class DemoRequestController {

    private final DemoRequestService service;

    /** 公开提交（无需登录） */
    @PostMapping("/public/demo-request")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody DemoRequestCreate request) {
        UUID id = service.create(request);
        return ApiResponse.success(Map.of("id", id));
    }

    /** 运营查询 */
    @GetMapping("/admin/demo-requests")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, size), 100);
        Page<DemoRequest> result = service.list(safePage, safeSize, status);
        return ApiResponse.success(PageResponse.of(result, safePage, safeSize));
    }

    /** 运营更新跟进状态：new / contacted / closed */
    @PatchMapping("/admin/demo-requests/{id}")
    public ApiResponse<Void> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody DemoRequestStatusUpdate request) {
        service.updateStatus(id, request.getStatus());
        return ApiResponse.success();
    }
}
