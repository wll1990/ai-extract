package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.Company;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillShare;
import com.aiextract.repository.CompanyRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SkillShareRepository;
import com.aiextract.service.ShareService;
import com.aiextract.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 对内分享控制器 — /i/{shareCode} 落地页信息。
 *
 * @author AI Extract Team
 * @since 2026-07-24
 */
@Slf4j
@RestController
@RequestMapping("/i")
@RequiredArgsConstructor
public class InternalShareController {

    private final SkillShareRepository shareRepository;
    private final SkillRepository skillRepository;
    private final CompanyRepository companyRepository;
    private final ShareService shareService;
    private final JwtUtil jwtUtil;

    private UUID extractUserId() {
        String token = (String) SecurityContextHolder.getContext().getAuthentication().getCredentials();
        return jwtUtil.getUserIdFromToken(token);
    }

    private String extractRole() {
        return jwtUtil.getRoleFromToken(
            (String) SecurityContextHolder.getContext().getAuthentication().getCredentials());
    }

    /**
     * 对内分享落地页信息。
     * GET /i/{shareCode}/info → 返回分身信息 + companyName
     * 前端据此展示登录页（companyId 预填）或直接进聊天。
     */
    @GetMapping("/{shareCode}/info")
    public ApiResponse<Map<String, Object>> getInfo(@PathVariable String shareCode) {
        SkillShare share = shareRepository.findByShareCode(shareCode)
            .orElseThrow(() -> new BusinessException(404, "分享链接不存在"));
        if (!SkillShare.CHANNEL_INTERNAL.equals(share.getChannel())) {
            throw new BusinessException(404, "分享链接不存在");
        }
        if (Boolean.FALSE.equals(share.getEnabled())) {
            throw new BusinessException(404, "分享链接已失效");
        }

        Skill skill = skillRepository.findById(share.getSkillId())
            .orElseThrow(() -> new BusinessException(404, "分身不存在"));
        if (!"published".equals(skill.getStatus()) && !"active".equals(skill.getStatus())) {
            throw new BusinessException(404, "分身未发布");
        }

        String companyName = null;
        if (share.getCompanyId() != null) {
            companyName = companyRepository.findById(share.getCompanyId())
                .map(Company::getName).orElse(null);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skillId", skill.getId().toString());
        result.put("companyId", share.getCompanyId() != null ? share.getCompanyId().toString() : null);
        result.put("companyName", companyName);
        result.put("ownerName", skill.getOwnerName());
        result.put("avatarUrl", skill.getAvatarUrl());
        return ApiResponse.success(result);
    }

    /**
     * 创建对内分享（分身属主可用）。
     */
    @PostMapping("/{skillId}/share/internal")
    public ApiResponse<Map<String, Object>> createInternalShare(
            @PathVariable UUID skillId) {
        SkillShare share = shareService.getOrCreateInternalShare(skillId, extractUserId(), extractRole());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skillId", share.getSkillId().toString());
        m.put("shareCode", share.getShareCode());
        m.put("channel", share.getChannel());
        m.put("enabled", share.getEnabled());
        m.put("createdAt", share.getCreatedAt() != null ? share.getCreatedAt().toString() : null);
        return ApiResponse.success(m);
    }
}
