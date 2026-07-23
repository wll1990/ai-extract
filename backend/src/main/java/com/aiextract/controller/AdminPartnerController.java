package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.PartnerCrypto;
import com.aiextract.model.PartnerApp;
import com.aiextract.model.PartnerApp.PartnerStatus;
import com.aiextract.repository.PartnerAppRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/admin/partners")
@RequiredArgsConstructor
public class AdminPartnerController {

    private final PartnerAppRepository repository;
    private final PartnerCrypto crypto;

    /** 合作方列表 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<PartnerApp> all = repository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (PartnerApp p : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId().toString());
            m.put("appId", p.getAppId());
            m.put("appName", p.getAppName());
            m.put("status", p.getStatus().name());
            m.put("contactName", p.getContactName());
            m.put("contactEmail", p.getContactEmail());
            m.put("createdAt", p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
            result.add(m);
        }
        return ApiResponse.success(result);
    }

    /** 新建合作方 — 返回 SK 明文（仅此一次） */
    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String appId = (String) body.get("appId");
        String appName = (String) body.get("appName");
        String contactName = (String) body.get("contactName");
        String contactEmail = (String) body.get("contactEmail");

        if (appId == null || appId.isBlank()) return ApiResponse.error(400, "appId 不能为空");
        if (repository.existsByAppId(appId)) return ApiResponse.error(400, "appId 已存在");

        String rawSK = crypto.generateSK();
        PartnerApp app = PartnerApp.builder()
            .appId(appId).appName(appName != null ? appName : appId)
            .secretKey(crypto.encrypt(rawSK))
            .status(PartnerStatus.ENABLED)
            .contactName(contactName).contactEmail(contactEmail)
            .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
            .build();
        repository.save(app);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", app.getId().toString());
        result.put("appId", app.getAppId());
        result.put("secretKey", rawSK); // 仅此一次明文返回
        return ApiResponse.success(result);
    }

    /** 编辑合作方 */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        PartnerApp app = repository.findById(id).orElse(null);
        if (app == null) return ApiResponse.error(404, "合作方不存在");

        if (body.containsKey("appName")) app.setAppName((String) body.get("appName"));
        if (body.containsKey("contactName")) app.setContactName((String) body.get("contactName"));
        if (body.containsKey("contactEmail")) app.setContactEmail((String) body.get("contactEmail"));
        app.setUpdatedAt(LocalDateTime.now());
        repository.save(app);
        return ApiResponse.success();
    }

    /** 启用/停用 */
    @PutMapping("/{id}/status")
    public ApiResponse<Void> toggleStatus(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        PartnerApp app = repository.findById(id).orElse(null);
        if (app == null) return ApiResponse.error(404, "合作方不存在");
        app.setStatus(PartnerStatus.valueOf(body.get("status")));
        app.setUpdatedAt(LocalDateTime.now());
        repository.save(app);
        return ApiResponse.success();
    }

    /** 重置 SK — 旧 SK 24h 过渡，返回新 SK 明文 */
    @PostMapping("/{id}/reset-sk")
    public ApiResponse<Map<String, String>> resetSK(@PathVariable UUID id) {
        PartnerApp app = repository.findById(id).orElse(null);
        if (app == null) return ApiResponse.error(404, "合作方不存在");

        // 旧 SK 过渡 24 小时
        app.setOldSecretKey(app.getSecretKey());
        app.setOldKeyExpiresAt(LocalDateTime.now().plusHours(24));

        String newSK = crypto.generateSK();
        app.setSecretKey(crypto.encrypt(newSK));
        app.setUpdatedAt(LocalDateTime.now());
        repository.save(app);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("secretKey", newSK);
        return ApiResponse.success(result);
    }
}
