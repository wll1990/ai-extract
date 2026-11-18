package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.PartnerDtos;
import com.aiextract.service.partner.PartnerSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 合作方 H5 用一次性 code 换会话（公开接口，code 单次使用 + 60s 过期）。
 */
@RestController
@RequestMapping("/c/auth/partner")
@RequiredArgsConstructor
public class PartnerExchangeController {

    private final PartnerSessionService partnerSessionService;

    @PostMapping("/exchange")
    public ApiResponse<PartnerDtos.ExchangeResponse> exchange(
            @RequestBody PartnerDtos.ExchangeRequest request) {
        return ApiResponse.success(
                partnerSessionService.exchange(request.appId(), request.code()));
    }
}
