package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.BaseController;
import com.aiextract.common.PageResponse;
import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillShare;
import com.aiextract.service.OrganizationSkillService;
import com.aiextract.service.ShareService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 组织分身管理接口 — 企业管理员 CRUD。
 *
 * @author AI Extract Team
 * @since 2026-07-28
 */
@Slf4j
@RestController
@RequestMapping("/admin/organization-skills")
@RequiredArgsConstructor
public class OrganizationSkillController extends BaseController {

    private final OrganizationSkillService orgSkillService;
    private final ShareService shareService;

    // ============================================================
    // 列表（分页）
    // ============================================================

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID companyId = TokenContext.getCompanyId();
        var orgPage = orgSkillService.listByCompanyPaged(companyId, status, page, size);
        List<Map<String, Object>> result = orgPage.getContent().stream()
                .map(orgSkillService::toApiMap)
                .collect(Collectors.toList());
        return ApiResponse.success(PageResponse.of(result, orgPage, page, size));
    }

    // ============================================================
    // 创建
    // ============================================================

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        String name = require(body, "name");
        String description = (String) body.getOrDefault("description", "");
        String avatarUrl = (String) body.getOrDefault("avatarUrl", null);
        @SuppressWarnings("unchecked")
        List<String> memberIds = (List<String>) body.getOrDefault("memberSkillIds", List.of());
        List<UUID> memberUuids = memberIds.stream().map(UUID::fromString).collect(Collectors.toList());
        if (memberUuids.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "请至少选择一位成员分身");
        }
        UUID companyId = TokenContext.getCompanyId();
        UUID userId = extractUserId();
        Skill org = orgSkillService.create(name, description, memberUuids, avatarUrl, companyId, userId);
        return ApiResponse.success(orgSkillService.toApiMap(org));
    }

    // ============================================================
    // 更新
    // ============================================================

    private Skill requireCompanyAccess(String id) {
        Skill org = orgSkillService.findById(UUID.fromString(id));
        UUID callerCompanyId = TokenContext.getCompanyId();
        if (!callerCompanyId.equals(org.getCompanyId())) {
            throw new BusinessException(403, "无权操作其他企业的组织分身");
        }
        return org;
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        requireCompanyAccess(id);
        String name = (String) body.get("name");
        String description = (String) body.get("description");
        String avatarUrl = (String) body.get("avatarUrl");
        @SuppressWarnings("unchecked")
        List<String> memberIds = (List<String>) body.get("memberSkillIds");
        List<UUID> memberUuids = memberIds != null
                ? memberIds.stream().map(UUID::fromString).collect(Collectors.toList())
                : null;
        Skill org = orgSkillService.update(UUID.fromString(id), name, description, memberUuids, avatarUrl);
        return ApiResponse.success(orgSkillService.toApiMap(org));
    }

    /** 组织分身头像上传 */
    @PostMapping(value = "/{id}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> uploadAvatar(@PathVariable String id,
                                                          @RequestParam("file") MultipartFile file) {
        requireCompanyAccess(id);
        String avatarUrl = orgSkillService.uploadAvatar(UUID.fromString(id), file);
        return ApiResponse.success(Map.of("avatarUrl", avatarUrl));
    }

    // ============================================================
    // 详情
    // ============================================================

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getDetail(@PathVariable String id) {
        requireCompanyAccess(id);
        return ApiResponse.success(orgSkillService.getDetail(UUID.fromString(id)));
    }

    /** 价值面板 — 组织分身使用数据概览 */
    @GetMapping("/{id}/dashboard")
    public ApiResponse<Map<String, Object>> getDashboard(@PathVariable String id) {
        requireCompanyAccess(id);
        return ApiResponse.success(orgSkillService.getDashboard(UUID.fromString(id)));
    }

    /** spaceId → skillId 映射 — 前端溯源卡片渲染成员链接 */
    @GetMapping("/{id}/member-links")
    public ApiResponse<Map<String, String>> getMemberLinks(@PathVariable String id) {
        requireCompanyAccess(id);
        var org = orgSkillService.findById(UUID.fromString(id));
        Map<UUID, UUID> map = orgSkillService.resolveSpaceToSkillMap(org);
        Map<String, String> result = new java.util.LinkedHashMap<>();
        map.forEach((spaceId, skillId) -> result.put(spaceId.toString(), skillId.toString()));
        return ApiResponse.success(result);
    }

    // ============================================================
    // 状态变更
    // ============================================================

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(
            @PathVariable String id, @RequestBody Map<String, String> body) {
        Skill org = requireCompanyAccess(id);
        String status = body.get("status");
        if (status == null || status.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "status 不能为空");
        }
        orgSkillService.updateStatus(UUID.fromString(id), status);
        if ("published".equals(status)) {
            // 初始化默认分享（对外默认关闭，对内默认开启）
            shareService.initDefaultShares(UUID.fromString(id), org.getCreatedBy(), false);
            // 异步生成 3 段式自我介绍
            orgSkillService.generateOrgIntroProfile(UUID.fromString(id));
        }
        return ApiResponse.success();
    }

    // ============================================================
    // 删除
    // ============================================================

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        requireCompanyAccess(id);
        orgSkillService.delete(UUID.fromString(id));
        return ApiResponse.success();
    }

    // ============================================================
    // 分享管理（镜像 AdminShareController 个体分身分享模式）
    // ============================================================

    @PostMapping("/{id}/share")
    public ApiResponse<Map<String, Object>> getOrCreateShare(@PathVariable String id,
            @RequestBody(required = false) Map<String, String> body) {
        requireCompanyAccess(id);
        String channel = body != null ? body.getOrDefault("channel", SkillShare.CHANNEL_PUBLIC) : SkillShare.CHANNEL_PUBLIC;
        SkillShare share = shareService.getOrCreateShare(UUID.fromString(id), extractUserId(), channel);
        return ApiResponse.success(toShareMap(share));
    }

    @GetMapping("/{id}/share")
    public ApiResponse<Map<String, Object>> getShare(@PathVariable String id) {
        requireCompanyAccess(id);
        return shareService.findShare(UUID.fromString(id))
                .map(s -> ApiResponse.success(toShareMap(s)))
                .orElseThrow(() -> new BusinessException(404, "尚未生成分享链接"));
    }

    @PutMapping("/{id}/share")
    public ApiResponse<Map<String, Object>> toggleShare(
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        requireCompanyAccess(id);
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        SkillShare share = shareService.toggleShare(UUID.fromString(id), enabled);
        return ApiResponse.success(toShareMap(share));
    }

    @PutMapping("/{id}/share/code")
    public ApiResponse<Map<String, Object>> updateShareCode(
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        requireCompanyAccess(id);
        String customCode = (String) body.get("shareCode");
        if (customCode == null || customCode.isBlank()) {
            throw new BusinessException(400, "shareCode 不能为空");
        }
        if (!customCode.matches("^[a-zA-Z0-9\\-]{4,30}$")) {
            throw new BusinessException(400, "短码格式不正确（4-30位，仅字母数字和短横线）");
        }
        SkillShare share = shareService.updateShareCode(UUID.fromString(id), customCode);
        return ApiResponse.success(toShareMap(share));
    }

    // ============================================================
    // helpers
    // ============================================================

    private String require(Map<String, Object> body, String key) {
        Object val = body.get(key);
        if (val == null || val.toString().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), key + " 不能为空");
        }
        return val.toString();
    }
}
