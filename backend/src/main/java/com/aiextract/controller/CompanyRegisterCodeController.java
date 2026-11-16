package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.CompanyRegisterCode;
import com.aiextract.service.CompanyRegisterCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 企业管理员自助生成员工注册码。
 *
 * <p>企业管理员只能操作本企业的「员工码」，不能生成管理员码（管理员码仍由平台超管下发）。
 * 企业归属只取 TokenContext，不接收请求体里的 companyId，避免越权。</p>
 */
@RestController
@RequestMapping("/admin/company/register-codes")
@RequiredArgsConstructor
public class CompanyRegisterCodeController {

    private final CompanyRegisterCodeService service;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.success(service.listEmployeeCodes(currentCompanyId()).stream()
                .map(this::toMap).toList());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        int maxUses = 0;
        LocalDateTime expiresAt = null;
        if (body != null) {
            if (body.get("maxUses") instanceof Number n && n.intValue() >= 0) {
                maxUses = n.intValue();
            }
            if (body.get("expiresInDays") instanceof Number d && d.intValue() > 0) {
                expiresAt = LocalDateTime.now().plusDays(d.intValue());
            }
        }
        CompanyRegisterCode code = service.create(currentCompanyId(), "employee", maxUses,
                expiresAt, TokenContext.get());
        return ApiResponse.success(toMap(code));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> toggle(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        Object enabledVal = body.get("enabled");
        if (enabledVal == null) {
            return ApiResponse.error(400, "enabled 不能为空");
        }
        boolean enabled = enabledVal instanceof Boolean b ? b : Boolean.parseBoolean(enabledVal.toString());
        service.setEnabled(currentCompanyId(), id, enabled);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.delete(currentCompanyId(), id);
        return ApiResponse.success();
    }

    private UUID currentCompanyId() {
        UUID companyId = TokenContext.getCompanyId();
        if (companyId == null) {
            throw new BusinessException(400, "当前账号未归属企业，无法生成员工注册码");
        }
        return companyId;
    }

    private Map<String, Object> toMap(CompanyRegisterCode c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId().toString());
        m.put("code", c.getCode());
        m.put("enabled", c.getEnabled());
        m.put("maxUses", c.getMaxUses());
        m.put("usedCount", c.getUsedCount());
        m.put("defaultRole", c.getDefaultRole());
        m.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        m.put("expiresAt", c.getExpiresAt() != null ? c.getExpiresAt().toString() : null);
        m.put("lastUsedAt", c.getLastUsedAt() != null ? c.getLastUsedAt().toString() : null);
        return m;
    }
}
