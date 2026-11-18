package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.TokenContext;
import com.aiextract.dto.PracticeKcSettingRequest;
import com.aiextract.dto.PracticeKcSettingView;
import com.aiextract.exception.BusinessException;
import com.aiextract.service.PracticeKcSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 对练知识/案例检索开关（企业级，保存后立即生效）。
 *
 * <p>公司维度取当前登录态的企业（超管需先切换到具体企业），避免平台级开关
 * 影响其他租户。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-16
 */
@RestController
@RequestMapping("/admin/company/practice-kc")
@RequiredArgsConstructor
public class PracticeKcSettingController {

    private final PracticeKcSettingService settingService;

    @GetMapping
    public ApiResponse<PracticeKcSettingView> get() {
        return ApiResponse.success(settingService.get(requireCompanyId()));
    }

    @PutMapping
    public ApiResponse<PracticeKcSettingView> update(@Valid @RequestBody PracticeKcSettingRequest request) {
        UUID companyId = requireCompanyId();
        return ApiResponse.success(
                settingService.update(companyId, Boolean.TRUE.equals(request.enabled()), TokenContext.get()));
    }

    private UUID requireCompanyId() {
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            throw new BusinessException(400, "请先切换到具体企业后再配置");
        }
        return companyId;
    }
}
