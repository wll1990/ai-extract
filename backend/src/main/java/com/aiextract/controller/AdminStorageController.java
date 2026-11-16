package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.config.CompanyScopeService;
import com.aiextract.exception.BusinessException;
import com.aiextract.service.StorageMigrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 存储运维：把存量本地文件迁移到 OSS。
 *
 * <p>仅平台超管可执行；默认 dryRun，确认数量后再传 dryRun=false。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-15
 */
@RestController
@RequestMapping("/admin/storage")
@RequiredArgsConstructor
public class AdminStorageController {

    private final CompanyScopeService companyScopeService;
    private final StorageMigrationService storageMigrationService;

    @PostMapping("/migrate-local")
    public ApiResponse<Map<String, Object>> migrateLocal(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        if (!companyScopeService.isSuperAdmin()) {
            throw new BusinessException(403, "仅平台管理员可执行存储迁移");
        }
        return ApiResponse.success(storageMigrationService.migrateLocal(dryRun));
    }
}
