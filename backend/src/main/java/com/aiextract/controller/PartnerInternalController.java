package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.PartnerDtos;
import com.aiextract.exception.PartnerException;
import com.aiextract.model.PartnerApp;
import com.aiextract.service.partner.PartnerHmacVerifier;
import com.aiextract.service.partner.PartnerSessionService;
import com.aiextract.service.session.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 合作方服务端接口 —— 全部走 HMAC 验签，SecurityConfig 放行后由本 Controller 校验。
 */
@RestController
@RequestMapping("/internal/partner")
@RequiredArgsConstructor
public class PartnerInternalController {

    private final PartnerHmacVerifier verifier;
    private final PartnerSessionService partnerSessionService;
    private final ObjectMapper objectMapper;

    @PostMapping("/session-codes")
    public ApiResponse<PartnerDtos.CodeResponse> issueCode(
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) throws Exception {
        PartnerApp app = verifier.verify(request, rawBody);
        if (rawBody == null || rawBody.isBlank()) {
            throw new PartnerException(400, "PARTNER_ARGUMENT_INVALID", "请求体不能为空");
        }
        PartnerDtos.CodeRequest body = objectMapper.readValue(rawBody, PartnerDtos.CodeRequest.class);
        return ApiResponse.success(partnerSessionService.issueCode(app, body));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<SessionService.DeviceView>> list(
            @RequestParam String externalUserId,
            HttpServletRequest request) {
        PartnerApp app = verifier.verify(request, "");
        return ApiResponse.success(partnerSessionService.list(app, externalUserId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> revoke(@PathVariable UUID sessionId, HttpServletRequest request) {
        PartnerApp app = verifier.verify(request, "");
        partnerSessionService.revoke(app, sessionId);
        return ApiResponse.success();
    }

    @PostMapping("/sessions/revoke-all")
    public ApiResponse<Void> revokeAll(
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) throws Exception {
        PartnerApp app = verifier.verify(request, rawBody);
        if (rawBody == null || rawBody.isBlank()) {
            throw new PartnerException(400, "PARTNER_ARGUMENT_INVALID", "请求体不能为空");
        }
        PartnerDtos.RevokeAllRequest body =
                objectMapper.readValue(rawBody, PartnerDtos.RevokeAllRequest.class);
        partnerSessionService.revokeAll(app, body.externalUserId());
        return ApiResponse.success();
    }
}
