package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.CompanyAiConfigRequest;
import com.aiextract.dto.CompanyAiConfigView;
import com.aiextract.dto.CompanyAiRotateRequest;
import com.aiextract.dto.CompanyAiTestRequest;
import com.aiextract.dto.CompanyAiTestResult;
import com.aiextract.exception.BusinessException;
import com.aiextract.service.CompanyAiConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 企业 AI Key 配置 —— 仅 company_admin / super_admin（AI_MODEL_MANAGE）。
 */
@RestController
@RequestMapping("/admin/company/ai-model")
@RequiredArgsConstructor
public class AdminCompanyAiController {

    private final CompanyAiConfigService service;

    @GetMapping
    public ApiResponse<CompanyAiConfigView> get() {
        return ApiResponse.success(service.get(companyId()));
    }

    @PutMapping
    public ApiResponse<CompanyAiConfigView> update(@Valid @RequestBody CompanyAiConfigRequest req) {
        return ApiResponse.success(service.update(companyId(), req));
    }

    @PostMapping("/test")
    public ApiResponse<CompanyAiTestResult> test(
            @RequestBody(required = false) CompanyAiTestRequest req) {
        return ApiResponse.success(service.test(companyId(), req));
    }

    @PostMapping("/rotate")
    public ApiResponse<Void> rotate(@Valid @RequestBody CompanyAiRotateRequest req) {
        service.rotate(companyId(), req.apiKey());
        return ApiResponse.success(null);
    }

    @PostMapping("/enable")
    public ApiResponse<Void> enable() {
        service.enable(companyId());
        return ApiResponse.success(null);
    }

    @PostMapping("/disable")
    public ApiResponse<Void> disable() {
        service.disable(companyId());
        return ApiResponse.success(null);
    }

    /** 最近 20 条配置变更：谁在什么时候启用/停用/改模型/换 Key。 */
    @GetMapping("/history")
    public ApiResponse<java.util.List<com.aiextract.dto.CompanyAiConfigHistoryView>> history() {
        return ApiResponse.success(service.history(companyId()));
    }

    /** 支持的厂商目录：前端据此渲染下拉与默认 Base URL/模型 */
    @GetMapping("/providers")
    public ApiResponse<java.util.List<java.util.Map<String, Object>>> providers() {
        return ApiResponse.success(com.aiextract.config.ProviderCatalog.all().stream()
                .map(p -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", p.id());
                    m.put("label", p.label());
                    m.put("defaultBaseUrl", p.defaultBaseUrl());
                    m.put("defaultModel", p.defaultModel());
                    m.put("completionsPath", p.completionsPath());
                    m.put("maxOutputTokens", p.maxOutputTokens());
                    m.put("contextWindow", p.contextWindow());
                    m.put("customBaseUrlAllowed", p.customBaseUrlAllowed());
                    return m;
                }).toList());
    }

    private UUID companyId() {
        UUID id = TokenContext.getCompanyId();
        if (id == null) {
            throw new BusinessException(403, "无企业归属，不能配置企业 AI Key");
        }
        return id;
    }
}
