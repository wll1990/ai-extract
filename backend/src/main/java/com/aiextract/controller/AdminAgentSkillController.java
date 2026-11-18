package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.AgentSkillDtos;
import com.aiextract.service.skill.AgentSkillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 模型技能管理（系统配置 → 模型技能）。
 *
 * <p>权限在 SecurityConfig 统一要求 SKILL_MANAGE；平台级写操作由 Service 额外校验
 * COMPANY_MANAGE，跨租户按 ID 访问返回 404。</p>
 */
@RestController
@RequestMapping("/admin/agent-skills")
@RequiredArgsConstructor
public class AdminAgentSkillController {

    private final AgentSkillService service;

    @GetMapping
    public ApiResponse<List<AgentSkillDtos.ListItem>> list(
            @RequestParam(defaultValue = "company") String scope,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source) {
        return ApiResponse.success(service.list(scope, keyword, source));
    }

    @GetMapping("/settings")
    public ApiResponse<AgentSkillDtos.SettingView> settings() {
        return ApiResponse.success(service.settings());
    }

    @PutMapping("/settings")
    public ApiResponse<Void> updateSettings(@RequestBody AgentSkillDtos.SettingRequest request) {
        service.updateSettings(request);
        return ApiResponse.success();
    }

    @PostMapping("/preview")
    public ApiResponse<AgentSkillDtos.PreviewResponse> preview(
            @RequestBody AgentSkillDtos.PreviewRequest request) {
        return ApiResponse.success(service.preview(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<AgentSkillDtos.Detail> detail(@PathVariable UUID id) {
        return ApiResponse.success(service.detail(id));
    }

    @GetMapping("/{id}/resources/{resourceId}")
    public ApiResponse<AgentSkillDtos.ResourceView> resource(@PathVariable UUID id,
                                                             @PathVariable UUID resourceId) {
        return ApiResponse.success(service.resourceView(id, resourceId));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<AgentSkillDtos.VersionItem>> versions(@PathVariable UUID id) {
        return ApiResponse.success(service.versions(id));
    }

    @PostMapping
    public ApiResponse<AgentSkillDtos.Detail> create(@RequestBody AgentSkillDtos.CreateRequest request) {
        return ApiResponse.success(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AgentSkillDtos.Detail> update(@PathVariable UUID id,
                                                     @RequestBody AgentSkillDtos.UpdateRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    @PostMapping("/{id}/new-version")
    public ApiResponse<AgentSkillDtos.Detail> newVersion(@PathVariable UUID id) {
        return ApiResponse.success(service.newVersion(id));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<AgentSkillDtos.Detail> publish(@PathVariable UUID id) {
        return ApiResponse.success(service.publish(id));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<Void> archive(@PathVariable UUID id) {
        service.archive(id);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteDraft(@PathVariable UUID id) {
        service.deleteDraft(id);
        return ApiResponse.success();
    }
}
