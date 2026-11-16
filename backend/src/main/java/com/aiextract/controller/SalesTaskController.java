package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.dto.StatusTransitionRequest;
import com.aiextract.dto.TaskEscalateRequest;
import com.aiextract.dto.TaskTransferRequest;
import com.aiextract.dto.TaskUpdateRequest;
import com.aiextract.dto.TaskView;
import com.aiextract.dto.UserOptionView;
import com.aiextract.service.SalesTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/sales/tasks")
@RequiredArgsConstructor
public class SalesTaskController {

    private final SalesTaskService service;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(PageResponse.of(service.list(page, size, status), page, size));
    }

    @GetMapping("/owners")
    public ApiResponse<Map<String, Object>> owners(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(PageResponse.of(
                service.owners(keyword, page, size), page, size));
    }

    @PutMapping("/{id}")
    public ApiResponse<TaskView> update(@PathVariable UUID id,
                                        @Valid @RequestBody TaskUpdateRequest req) {
        return ApiResponse.success(service.update(id, req));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<TaskView> updateStatus(
            @PathVariable UUID id, @Valid @RequestBody StatusTransitionRequest req) {
        return ApiResponse.success(service.updateStatus(id, req.status()));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<TaskView> transfer(@PathVariable UUID id,
                                          @Valid @RequestBody TaskTransferRequest req) {
        return ApiResponse.success(service.transfer(id, req.ownerId()));
    }

    @PostMapping("/{id}/escalate")
    public ApiResponse<TaskView> escalate(@PathVariable UUID id,
                                          @Valid @RequestBody(required = false) TaskEscalateRequest req) {
        return ApiResponse.success(service.escalate(id, req == null ? null : req.reason()));
    }
}
