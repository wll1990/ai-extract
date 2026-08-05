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
import com.aiextract.model.SkillShare;

import com.aiextract.repository.SkillConversationRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SkillShareRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    
    private final com.aiextract.repository.SkillShareRepository shareRepository;
    private final com.aiextract.service.SkillService skillService;
    private final com.aiextract.service.OrganizationSkillService orgSkillService;
    private final ObjectMapper objectMapper;

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
     * 公开分身详情 — 平台端专家名片页，无需登录。
     * 仅返回已发布且开启对外分享的分身基本资料。
     */
    @GetMapping("/skills/{skillId}")
    public ApiResponse<Map<String, Object>> getPublicSkillDetail(@PathVariable String skillId) {
        Skill skill = skillRepository.findById(UUID.fromString(skillId)).orElse(null);
        if (skill == null || !"published".equals(skill.getStatus())) {
            throw new BusinessException(404, "分身不存在");
        }
        SkillShare share = shareRepository.findFirstBySkillIdAndChannel(
                skill.getId(), SkillShare.CHANNEL_PUBLIC)
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                .orElseThrow(() -> new BusinessException(404, "分身未开放"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", skill.getId().toString());
        detail.put("shareCode", share.getShareCode());
        detail.put("displayName", skill.getDisplayName() != null ? skill.getDisplayName() : skill.getOwnerName());
        detail.put("ownerName", skill.getOwnerName());
        detail.put("ownerTitle", skill.getOwnerTitle());
        detail.put("avatarUrl", skill.getAvatarUrl());
        detail.put("department", skill.getDepartment());
        List<String> tagList = parseTags(skill.getTags());
        detail.put("tags", tagList);
        // sceneTags: 从颗粒按场景分组统计，展示"🎯 N 个业务场景"
        try {
            detail.put("sceneTags", skillService.getSceneTags(skillId));
        } catch (Exception e) {
            detail.put("sceneTags", List.of());
        }
        detail.put("grainCount", grainRepository.countBySpaceId(skill.getSpaceId()));
        detail.put("openingMessage", skill.getOpeningMessage());
        detail.put("domain", skill.getDomain());
        String skillType = skill.getType() != null ? skill.getType() : "individual";
        detail.put("type", skillType);
        // 组织分身：成员列表 + 成员数
        if ("organization".equals(skillType)) {
            List<com.aiextract.model.Skill> members = orgSkillService.resolveMembers(skill);
            detail.put("memberCount", members.size());
            detail.put("members", members.stream().map(m -> {
                Map<String, Object> mb = new LinkedHashMap<>();
                mb.put("id", m.getId().toString());
                mb.put("displayName", m.getDisplayName());
                mb.put("ownerName", m.getOwnerName());
                mb.put("ownerTitle", m.getOwnerTitle());
                mb.put("avatarUrl", m.getAvatarUrl());
                mb.put("department", m.getDepartment());
                mb.put("domain", m.getDomain());
                mb.put("conversationCount", m.getConversationCount() != null ? m.getConversationCount() : 0);
                return mb;
            }).toList());
        }
        // 推荐问题: 优先从 sceneTags 生成，次选 tags
        List<String> questions = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stList = (List<Map<String, Object>>) detail.get("sceneTags");
        if (stList != null && !stList.isEmpty()) {
            for (int i = 0; i < Math.min(stList.size(), 5); i++) {
                questions.add("关于「" + stList.get(i).get("tag") + "」，你能分享一个实战案例吗？");
            }
        } else {
            for (int i = 0; i < Math.min(tagList.size(), 5); i++) {
                questions.add("关于" + tagList.get(i) + "，你能分享一下经验吗？");
            }
        }
        detail.put("recommendedQuestions", questions);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("conversationCount", skill.getConversationCount() != null ? skill.getConversationCount() : 0);
        stats.put("userCount", skill.getUserCount() != null ? skill.getUserCount() : 0);
        stats.put("satisfactionRate", skill.getSatisfactionRate() != null ? skill.getSatisfactionRate() : 0);
        detail.put("stats", stats);
        detail.put("introProfile", skill.getIntroProfile());
        return ApiResponse.success(detail);
    }

    /**
     * 公开分身列表 — 发现页浏览。
     * 只返回已发布分身的基本信息，无需认证。
     */
    @GetMapping("/skills")
    public ApiResponse<Map<String, Object>> listPublicSkills(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "recommended") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(Math.max(size, 1), 50); // 防拖库，上限 50
        List<Skill> skills = skillRepository.findByStatus("published");

        // 只展示已开启对外分享的分身
        Set<UUID> sharedSkillIds = shareRepository
                .findByChannelAndEnabled(SkillShare.CHANNEL_PUBLIC, true).stream()
                .map(SkillShare::getSkillId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        skills = skills.stream()
                .filter(s -> sharedSkillIds.contains(s.getId()))
                .toList();

        // 批量查颗粒数
        List<UUID> spaceIds = skills.stream().map(Skill::getSpaceId).distinct().toList();
        Map<UUID, Long> grainCountMap = grainRepository.countBySpaceIdIn(spaceIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (UUID) row[0], row -> (Long) row[1], (a, b) -> a));

        String q = search.trim().toLowerCase();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Skill skill : skills) {
            String name = skill.getDisplayName() != null ? skill.getDisplayName()
                    : skill.getOwnerName() != null ? skill.getOwnerName() : "";
            String title = skill.getOwnerTitle() != null ? skill.getOwnerTitle() : "";
            List<String> tagList = parseTags(skill.getTags());
            String skillType = skill.getType() != null ? skill.getType() : "individual";

            // 搜索过滤：姓名、标签、领域
            if (!q.isBlank()) {
                boolean hit = name.toLowerCase().contains(q)
                        || title.toLowerCase().contains(q)
                        || tagList.stream().anyMatch(t -> t.toLowerCase().contains(q))
                        || (skill.getDomain() != null && skill.getDomain().toLowerCase().contains(q));
                if (!hit) continue;
            }

            // 类型过滤
            if (!type.isBlank() && !type.equals(skillType)) continue;

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
            item.put("type", skillType);
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("conversationCount", skill.getConversationCount() != null ? skill.getConversationCount() : 0);
            stats.put("userCount", skill.getUserCount() != null ? skill.getUserCount() : 0);
            stats.put("satisfactionRate", skill.getSatisfactionRate() != null ? skill.getSatisfactionRate() : 0);
            item.put("stats", stats);
            result.add(item);
        }

        // 排序
        switch (sort) {
            case "popular":
                result.sort((a, b) -> Integer.compare(
                        (int) ((Map<String, Object>) b.get("stats")).getOrDefault("conversationCount", 0),
                        (int) ((Map<String, Object>) a.get("stats")).getOrDefault("conversationCount", 0)));
                break;
            case "grains":
                result.sort((a, b) -> Integer.compare(
                        (int) b.getOrDefault("grainCount", 0),
                        (int) a.getOrDefault("grainCount", 0)));
                break;
            default: // recommended
                result.sort((a, b) -> Integer.compare(
                        (int) b.getOrDefault("grainCount", 0),
                        (int) a.getOrDefault("grainCount", 0)));
        }

        // 分页
        int total = result.size();
        int totalPages = (int) Math.ceil((double) total / size);
        int fromIndex = (page - 1) * size;
        int toIndex = Math.min(fromIndex + size, total);
        List<Map<String, Object>> paged = fromIndex < total ? result.subList(fromIndex, toIndex) : List.of();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", paged);
        response.put("page", page);
        response.put("size", size);
        response.put("total", total);
        response.put("totalPages", totalPages);

        return ApiResponse.success(response);
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
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", c.getType() != null ? c.getType() : "enterprise");
        if ("personal".equals(c.getType())) {
            result.put("inviterName", c.getInvitedBy() != null ? c.getInvitedBy() : "平台用户");
        } else {
            Company company = companyRepository.findById(c.getCompanyId()).orElse(null);
            result.put("companyId", c.getCompanyId() != null ? c.getCompanyId().toString() : null);
            result.put("companyName", company != null ? company.getName() : "未知企业");
        }
        return ApiResponse.success(result);
    }

    @SuppressWarnings("unchecked")
    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank() || EMPTY_JSON_ARRAY.equals(tagsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
