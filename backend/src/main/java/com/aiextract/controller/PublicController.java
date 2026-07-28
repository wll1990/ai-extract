package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.Company;
import com.aiextract.model.CompanyRegisterCode;
import com.aiextract.model.InterviewInviteCode;
import com.aiextract.model.Skill;
import com.aiextract.repository.CompanyRegisterCodeRepository;
import com.aiextract.repository.CompanyRepository;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.InterviewInviteCodeRepository;
import com.aiextract.repository.SkillConversationRepository;
import com.aiextract.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 对外公开接口 — SecurityConfig 中 /public/** permitAll。
 *
 * <p>为 System B 落地页和发现页提供无需认证的数据。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-20
 */
@Slf4j
@RestController
@RequestMapping("/public")
@RequiredArgsConstructor
public class PublicController {

    private static final String EMPTY_JSON_ARRAY = "[]";

    private final SkillRepository skillRepository;
    private final ExperienceGrainRepository grainRepository;
    private final SkillConversationRepository conversationRepository;
    private final CompanyRegisterCodeRepository registerCodeRepository;
    private final InterviewInviteCodeRepository inviteCodeRepository;
    private final CompanyRepository companyRepository;

    /**
     * 落地页数据背书 — 平台实时统计数据。
     * 返回已发布分身数、活跃颗粒数、累计对话数。
     */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getStats() {
        long publishedSkills = skillRepository.findByStatus("published").size();
        long totalGrains = grainRepository.countByStatus("active");
        long totalConversations = conversationRepository.count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("publishedSkills", publishedSkills);
        stats.put("totalGrains", totalGrains);
        stats.put("totalConversations", totalConversations);
        return ApiResponse.success(stats);
    }

    /**
     * 公开分身列表 — 发现页浏览。
     * 只返回已发布分身的基本信息，无需认证。
     */
    @GetMapping("/skills")
    public ApiResponse<List<Map<String, Object>>> listPublicSkills(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String topic) {
        List<Skill> skills = skillRepository.findByStatus("published");

        // 批量查颗粒数
        List<UUID> spaceIds = skills.stream().map(Skill::getSpaceId).distinct().toList();
        Map<UUID, Long> grainCountMap = grainRepository.countBySpaceIdIn(spaceIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (UUID) row[0], row -> (Long) row[1], (a, b) -> a));

        // 收集所有话题标签
        Set<String> allTopics = new LinkedHashSet<>();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Skill skill : skills) {
            // 搜索过滤
            String name = skill.getDisplayName() != null ? skill.getDisplayName()
                    : skill.getOwnerName() != null ? skill.getOwnerName() : "";
            String title = skill.getOwnerTitle() != null ? skill.getOwnerTitle() : "";
            if (!search.isBlank()) {
                String q = search.toLowerCase();
                if (!name.toLowerCase().contains(q) && !title.toLowerCase().contains(q)) {
                    continue;
                }
            }

            // 话题过滤 — 从 tags JSONB 匹配
            List<String> tagList = parseTags(skill.getTags());
            if (!topic.isBlank() && !tagList.contains(topic)) {
                continue;
            }
            allTopics.addAll(tagList);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", skill.getId().toString());
            item.put("displayName", name);
            item.put("ownerName", skill.getOwnerName());
            item.put("ownerTitle", title);
            item.put("avatarUrl", skill.getAvatarUrl());
            item.put("department", skill.getDepartment());
            item.put("tags", tagList);
            item.put("grainCount", grainCountMap.getOrDefault(skill.getSpaceId(), 0L).intValue());
            item.put("openingMessage", skill.getOpeningMessage());
            item.put("domain", skill.getDomain());
            result.add(item);
        }

        // 按颗粒数降序
        result.sort((a, b) -> Integer.compare(
                (int) b.getOrDefault("grainCount", 0),
                (int) a.getOrDefault("grainCount", 0)));

        return ApiResponse.success(result);
    }

    /**
     * 查询企业注册码信息 — H5 注册页预填公司名。
     */
    @GetMapping("/company-code/{code}")
    public ApiResponse<Map<String, Object>> getCompanyCodeInfo(@PathVariable String code) {
        CompanyRegisterCode c = registerCodeRepository.findByCode(code)
            .orElseThrow(() -> new BusinessException(404, "注册码无效"));
        if (Boolean.FALSE.equals(c.getEnabled())) {
            throw new BusinessException(404, "注册码已失效");
        }
        if (c.getExpiresAt() != null && c.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(404, "注册码已过期");
        }
        Company company = companyRepository.findById(c.getCompanyId()).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("companyId", c.getCompanyId().toString());
        result.put("companyName", company != null ? company.getName() : "未知企业");
        return ApiResponse.success(result);
    }

    /**
     * 查询访谈邀请码信息 — H5 邀请页预填公司名。
     */
    @GetMapping("/invite/{inviteCode}")
    public ApiResponse<Map<String, Object>> getInviteInfo(@PathVariable String inviteCode) {
        InterviewInviteCode c = inviteCodeRepository.findByCode(inviteCode)
            .orElseThrow(() -> new BusinessException(404, "邀请码无效或已过期"));
        if (Boolean.FALSE.equals(c.getEnabled())) {
            throw new BusinessException(404, "邀请码已失效");
        }
        if (c.getExpiresAt() != null && c.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(404, "邀请码已过期");
        }
        Company company = companyRepository.findById(c.getCompanyId()).orElse(null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("companyId", c.getCompanyId().toString());
        result.put("companyName", company != null ? company.getName() : "未知企业");
        return ApiResponse.success(result);
    }

    @SuppressWarnings("unchecked")
    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank() || EMPTY_JSON_ARRAY.equals(tagsJson)) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(tagsJson, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
