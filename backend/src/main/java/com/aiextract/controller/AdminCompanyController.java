package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.model.Company;
import com.aiextract.model.CompanyRegisterCode;
import com.aiextract.repository.CompanyRegisterCodeRepository;
import com.aiextract.repository.CompanyRepository;
import com.aiextract.service.CompanyRegisterCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 企业管理 + 注册码生成 — 管理员后台。
 *
 * <p>注册码消费端见 {@link AuthController#registerWithCode}。本控制器补齐生产端：
 * 企业 CRUD 和按企业生成/列表/启停/删除注册码。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-28
 */
@RestController
@RequestMapping("/admin/companies")
@RequiredArgsConstructor
public class AdminCompanyController {

    private final CompanyRepository companyRepository;
    private final CompanyRegisterCodeRepository registerCodeRepository;
    private final CompanyRegisterCodeService registerCodeService;

    // ═══════════════════════════════════════════════════════════
    // 企业 CRUD
    // ═══════════════════════════════════════════════════════════

    /** 企业列表（分页） */
    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pageable = org.springframework.data.domain.PageRequest.of(page - 1, size);
        var companyPage = companyRepository.findAll(pageable);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Company c : companyPage.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId().toString());
            m.put("name", c.getName());
            m.put("logoUrl", c.getLogoUrl());
            m.put("brandColor", c.getBrandColor());
            m.put("contactName", c.getContactName());
            m.put("contactPhone", c.getContactPhone());
            m.put("contactEmail", c.getContactEmail());
            m.put("address", c.getAddress());
            m.put("industry", c.getIndustry());
            m.put("scale", c.getScale());
            m.put("notes", c.getNotes());
            m.put("status", c.getStatus() != null ? c.getStatus() : "active");
            m.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
            result.add(m);
        }
        return ApiResponse.success(PageResponse.of(result, companyPage, page, size));
    }

    /** 新建企业 */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) return ApiResponse.error(400, "企业名称不能为空");

        Company company = Company.builder()
            .id(UUID.randomUUID())
            .name(name.trim())
            .logoUrl((String) body.get("logoUrl"))
            .brandColor((String) body.get("brandColor"))
            .contactName((String) body.get("contactName"))
            .contactPhone((String) body.get("contactPhone"))
            .contactEmail((String) body.get("contactEmail"))
            .address((String) body.get("address"))
            .industry((String) body.get("industry"))
            .scale((String) body.get("scale"))
            .notes((String) body.get("notes"))
            .status("active")
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
        companyRepository.save(company);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", company.getId().toString());
        result.put("name", company.getName());
        return ApiResponse.success(result);
    }

    /** 编辑企业 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        Company c = companyRepository.findById(id).orElse(null);
        if (c == null) return ApiResponse.error(404, "企业不存在");

        if (body.containsKey("name")) c.setName((String) body.get("name"));
        if (body.containsKey("logoUrl")) c.setLogoUrl((String) body.get("logoUrl"));
        if (body.containsKey("brandColor")) c.setBrandColor((String) body.get("brandColor"));
        if (body.containsKey("contactName")) c.setContactName((String) body.get("contactName"));
        if (body.containsKey("contactPhone")) c.setContactPhone((String) body.get("contactPhone"));
        if (body.containsKey("contactEmail")) c.setContactEmail((String) body.get("contactEmail"));
        if (body.containsKey("address")) c.setAddress((String) body.get("address"));
        if (body.containsKey("industry")) c.setIndustry((String) body.get("industry"));
        if (body.containsKey("scale")) c.setScale((String) body.get("scale"));
        if (body.containsKey("notes")) c.setNotes((String) body.get("notes"));
        c.setUpdatedAt(LocalDateTime.now());
        companyRepository.save(c);
        return ApiResponse.success();
    }

    /** 启用/归档企业 */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> toggleStatus(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String status = body.get("status");
        if (status == null || (!status.equals("active") && !status.equals("archived"))) {
            return ApiResponse.error(400, "status 取值只能为 active 或 archived");
        }
        Company c = companyRepository.findById(id).orElse(null);
        if (c == null) return ApiResponse.error(404, "企业不存在");
        c.setStatus(status);
        c.setUpdatedAt(LocalDateTime.now());
        companyRepository.save(c);
        return ApiResponse.success();
    }

    // ═══════════════════════════════════════════════════════════
    // 注册码管理
    // ═══════════════════════════════════════════════════════════

    /** 某企业的注册码列表 */
    @GetMapping("/{companyId}/codes")
    public ApiResponse<List<Map<String, Object>>> listCodes(@PathVariable UUID companyId) {
        List<CompanyRegisterCode> codes = registerCodeRepository.findByCompanyIdOrderByCreatedAtDesc(companyId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (CompanyRegisterCode c : codes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId().toString());
            m.put("code", c.getCode());
            m.put("enabled", c.getEnabled());
            m.put("maxUses", c.getMaxUses());
            m.put("usedCount", c.getUsedCount());
            m.put("createdAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
            m.put("expiresAt", c.getExpiresAt() != null ? c.getExpiresAt().toString() : null);
            m.put("defaultRole", c.getDefaultRole());
            m.put("lastUsedBy", c.getLastUsedBy() != null ? c.getLastUsedBy().toString() : null);
            m.put("lastUsedAt", c.getLastUsedAt() != null ? c.getLastUsedAt().toString() : null);
            result.add(m);
        }
        return ApiResponse.success(result);
    }

    /** 生成注册码 — 返回明文 code（仅此一次） */
    @PostMapping("/{companyId}/codes")
    public ApiResponse<Map<String, Object>> generateCode(@PathVariable UUID companyId,
                                                         @RequestBody Map<String, Object> body) {
        if (!companyRepository.existsById(companyId)) {
            return ApiResponse.error(404, "企业不存在");
        }

        String defaultRole = body != null && body.get("defaultRole") instanceof String s
            && com.aiextract.config.RolePermissions.REGISTRABLE_ROLES.contains(s) ? s : "employee";

        LocalDateTime now = LocalDateTime.now();
        int maxUses = 0;
        if (body != null && body.get("maxUses") instanceof Number n) {
            maxUses = n.intValue();
        }
        if (com.aiextract.config.RolePermissions.COMPANY_ADMIN.equals(defaultRole)) {
            // 企业管理员码：一次性。限制的是「用几次」——码被复制后只有第一个用的人生效，
            // 之后全部失败。有效期不强制，由超管按需选择。
            maxUses = 1;
        }
        // 有效期默认不设，由超管决定（0 或不传 = 不限期）
        LocalDateTime expiresAt = null;
        if (body != null && body.get("expiresInDays") instanceof Number d && d.intValue() > 0) {
            expiresAt = now.plusDays(d.intValue());
        }

        CompanyRegisterCode crc = registerCodeService.create(companyId, defaultRole, maxUses, expiresAt, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", crc.getId().toString());
        result.put("code", crc.getCode());
        result.put("maxUses", crc.getMaxUses());
        result.put("defaultRole", crc.getDefaultRole());
        result.put("expiresAt", crc.getExpiresAt() != null ? crc.getExpiresAt().toString() : null);
        return ApiResponse.success(result);
    }

    /** 启停注册码 */
    @PutMapping("/{companyId}/codes/{codeId}/status")
    public ApiResponse<Void> toggleCodeStatus(@PathVariable UUID companyId,
                                              @PathVariable UUID codeId,
                                              @RequestBody Map<String, Object> body) {
        CompanyRegisterCode c = registerCodeRepository.findById(codeId).orElse(null);
        if (c == null || !c.getCompanyId().equals(companyId)) {
            return ApiResponse.error(404, "注册码不存在");
        }
        Object enabledVal = body.get("enabled");
        if (enabledVal == null) return ApiResponse.error(400, "enabled 不能为空");
        boolean enabled = enabledVal instanceof Boolean b ? b : Boolean.parseBoolean(enabledVal.toString());
        c.setEnabled(enabled);
        registerCodeRepository.save(c);
        return ApiResponse.success();
    }

    /** 删除注册码 */
    @DeleteMapping("/{companyId}/codes/{codeId}")
    public ApiResponse<Void> deleteCode(@PathVariable UUID companyId,
                                        @PathVariable UUID codeId) {
        CompanyRegisterCode c = registerCodeRepository.findById(codeId).orElse(null);
        if (c == null || !c.getCompanyId().equals(companyId)) {
            return ApiResponse.error(404, "注册码不存在");
        }
        registerCodeRepository.delete(c);
        return ApiResponse.success();
    }

}
