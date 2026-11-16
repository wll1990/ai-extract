package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.dto.CoachLedgerRow;
import com.aiextract.service.SalesCoachLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/sales/coach/ledger")
@RequiredArgsConstructor
public class SalesCoachLedgerController {

    private final SalesCoachLedgerService service;

    @GetMapping
    public ApiResponse<Map<String, Object>> ledger(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(PageResponse.of(service.ledger(page, size), page, size));
    }

    @GetMapping("/{sessionId}/transcript")
    public ApiResponse<Map<String, Object>> transcript(@PathVariable UUID sessionId) {
        return ApiResponse.success(service.transcript(sessionId));
    }
}
