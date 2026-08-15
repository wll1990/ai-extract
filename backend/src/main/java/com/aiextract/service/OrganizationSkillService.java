package com.aiextract.service;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.Skill;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 组织分身 Service — CRUD、成员解析、统计聚合、API 响应组装。
 *
 * <p>成员变动感知：getDetail 返回 inactiveMembers（memberSkillIds 中存在但非 published 的成员），
 * 管理员可据此感知已下线成员。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrganizationSkillService {

    private final SkillRepository skillRepository;
    private final ExperienceGrainRepository grainRepository;
    private final ObjectMapper objectMapper;
    private final PracticeDemoService practiceDemoService;
    private final ChatStreamAdapter chatStreamAdapter;
    private final OssService ossService;

    // ============================================================
    // CRUD
    // ============================================================

    @Transactional(rollbackFor = Exception.class)
    public Skill create(String name, String description,
                                     List<UUID> memberSkillIds,
                                     String avatarUrl, UUID companyId, UUID createdBy) {
        Skill org = Skill.builder().type("organization").spaceId(null)
                .id(UUID.randomUUID())
                .companyId(companyId)
                .displayName(name)
                .description(description)
                .memberSkillIds(toJson(memberSkillIds))
                .avatarUrl(avatarUrl)
                .status("draft")
                .conversationCount(0)
                .userCount(0)
                .satisfactionRate(0)
                .createdBy(createdBy)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Skill saved = skillRepository.save(org);
        log.info("组织分身已创建 id={} name={} memberCount={}", saved.getId(), name, memberSkillIds.size());
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public Skill update(UUID id, String name, String description,
                                     List<UUID> memberSkillIds, String avatarUrl) {
        Skill org = skillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "组织分身不存在"));
        if (name != null) org.setDisplayName(name);
        if (description != null) org.setDescription(description);
        if (memberSkillIds != null) org.setMemberSkillIds(toJson(memberSkillIds));
        if (avatarUrl != null) org.setAvatarUrl(avatarUrl);
        Skill updated = skillRepository.save(org);
        log.info("组织分身已更新 id={} name={}", updated.getId(), updated.getDisplayName());
        return updated;
    }

    /** 组织分身头像上传到 OSS */
    @Transactional(rollbackFor = Exception.class)
    public String uploadAvatar(UUID orgSkillId, MultipartFile file) {
        Skill org = skillRepository.findById(orgSkillId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "组织分身不存在"));

        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "avatar";
        String safeName = System.currentTimeMillis() + "_" + originalName.replaceAll("[^a-zA-Z0-9._\\-]", "_");
        String objectKey = "avatars/skills/" + orgSkillId + "/" + safeName;
        String avatarUrl = ossService.upload(objectKey, file);

        org.setAvatarUrl(avatarUrl);
        skillRepository.save(org);
        log.info("组织分身头像已更新 orgSkillId={}", orgSkillId);
        return avatarUrl;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(UUID id) {
        skillRepository.deleteById(id);
        log.info("组织分身已删除 id={}", id);
    }

    /**
     * 级联清理 — 成员分身废弃/撤发布时，从所有组织分身的 member_skill_ids 中移除该 ID。
     *
     * @param memberSkillId 被废弃/撤发布的成员 Skill ID
     * @return 实际清理的组织分身数量
     */
    @Transactional(rollbackFor = Exception.class)
    public int removeMemberFromAllOrgSkills(UUID memberSkillId) {
        List<Skill> orgSkills = skillRepository.findByTypeAndStatusIn("organization",
                List.of("published", "draft", "reviewing"));
        int cleaned = 0;
        for (Skill org : orgSkills) {
            List<UUID> ids = JsonUtil.parseList(org.getMemberSkillIds(), UUID::fromString);
            if (ids.remove(memberSkillId)) {
                org.setMemberSkillIds(toJson(ids));
                skillRepository.save(org);
                cleaned++;
            }
        }
        if (cleaned > 0) {
            log.info("级联清理成员: memberSkillId={} 已从 {} 个组织分身移除", memberSkillId, cleaned);
        }
        return cleaned;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(UUID id, String status) {
        Skill org = skillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "组织分身不存在"));
        org.setStatus(status);
        org.setUpdatedAt(LocalDateTime.now());
        skillRepository.save(org);
        log.info("组织分身状态已更新 id={} status={}", id, status);
    }

    /**
     * 异步生成组织分身 3 段式自我介绍 — 基于部门名 + 描述 + 成员信息。
     * 发布时触发，写入 org.introProfile。幂等：已有值则跳过。
     */
    @org.springframework.scheduling.annotation.Async("embeddingExecutor")
    public void generateOrgIntroProfile(UUID orgId) {
        try {
            Skill org = skillRepository.findById(orgId).orElse(null);
            if (org == null) return;
            // 幂等：已生成则跳过
            if (org.getIntroProfile() != null && !org.getIntroProfile().isBlank()
                    && !"{}".equals(org.getIntroProfile())) {
                return;
            }
            List<UUID> memberIds = JsonUtil.parseList(org.getMemberSkillIds(), UUID::fromString);
            List<Skill> members = memberIds.isEmpty() ? List.of()
                : skillRepository.findAllById(memberIds).stream()
                    .filter(s -> "published".equals(s.getStatus())).toList();
            String memberNames = members.stream()
                .map(m -> m.getOwnerName() != null ? m.getOwnerName() : "销冠")
                .limit(5).collect(java.util.stream.Collectors.joining("、"));
            if (memberNames.isEmpty()) memberNames = "团队成员";

            // 用类似 skill_opening_message 的 prompt 模板（3 段式 JSON），适配组织场景
            String prompt = String.format("""
                你是品牌文案专家。为以下部门团队生成"名片页自我介绍"JSON。

                要求：
                1. 输出严格 JSON，三个字段：headline、body、closing
                2. headline（10-20 字）：一句话概括部门定位
                3. body（40-80 字）：部门核心能力+覆盖场景+成员画像
                4. closing（10-20 字）：收尾金句——温暖、有人味、邀请对话
                5. 以"我们"第一人称
                6. 不编造数据，只基于输入的信息

                输入：
                - 部门名：%s
                - 部门描述：%s
                - 成员：%s
                - 领域：%s

                只输出 JSON，不加 markdown 代码块标记。""",
                org.getDisplayName(),
                org.getDescription() != null ? org.getDescription() : "",
                memberNames,
                org.getDomain() != null ? org.getDomain() : "sales");

            String generated = chatStreamAdapter.chat(prompt);
            if (generated != null && !generated.isBlank()) {
                String jsonStr = generated.trim()
                    .replaceAll("^```(?:json)?\\s*", "")
                    .replaceAll("\\s*```$", "");
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, String> introMap = objectMapper.readValue(jsonStr, Map.class);
                    if (introMap.containsKey("headline") || introMap.containsKey("body")) {
                        Map<String, String> cleaned = new LinkedHashMap<>();
                        cleaned.put("headline", introMap.getOrDefault("headline", "").trim());
                        cleaned.put("body", introMap.getOrDefault("body", "").trim());
                        cleaned.put("closing", introMap.getOrDefault("closing", "").trim());
                        org.setIntroProfile(objectMapper.writeValueAsString(cleaned));
                        org.setOpeningMessage(cleaned.get("headline"));
                        skillRepository.save(org);
                        log.info("组织分身 introProfile 已生成 orgId={}", orgId);
                    }
                } catch (Exception e) {
                    log.warn("组织分身 introProfile JSON 解析失败 orgId={}", orgId);
                }
            }
        } catch (Exception e) {
            log.error("组织分身 introProfile 生成失败 orgId={}", orgId, e);
        }
    }

    // ============================================================
    // 查询
    // ============================================================

    @Transactional(readOnly = true)
    public List<Skill> listByCompany(UUID companyId, String status) {
        if (status != null && !status.isEmpty()) {
            return skillRepository.findByCompanyIdAndStatus(companyId, status);
        }
        return skillRepository.findByCompanyId(companyId);
    }

    /** 分页版 — Controller 委托查询，不再直接操作 Repository。 */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Skill> listByCompanyPaged(UUID companyId, String status,
            int page, int size) {
        var pageable = org.springframework.data.domain.PageRequest.of(page - 1, size);
        if (status != null && !status.isEmpty()) {
            return skillRepository.findByCompanyIdAndStatus(companyId, status, pageable);
        }
        return skillRepository.findByCompanyId(companyId, pageable);
    }

    @Transactional(readOnly = true)
    public Skill findById(UUID id) {
        return skillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "组织分身不存在"));
    }

    // ============================================================
    // 成员解析
    // ============================================================

    /**
     * 解析成员 Skill 实体 — 仅返回 published 状态的成员。
     */
    @Transactional(readOnly = true)
    public List<Skill> resolveMembers(Skill org) {
        List<UUID> ids = JsonUtil.parseList(org.getMemberSkillIds(), UUID::fromString);
        if (ids.isEmpty()) return List.of();
        return skillRepository.findAllById(ids).stream()
                .filter(s -> "published".equals(s.getStatus()))
                .collect(Collectors.toList());
    }

    /**
     * 提取成员 spaceId 列表 — 供多空间 RAG 检索使用。
     */
    @Transactional(readOnly = true)
    public List<UUID> resolveMemberSpaceIds(Skill org) {
        return resolveMembers(org).stream()
                .map(Skill::getSpaceId)
                .distinct()
                .collect(Collectors.toList());
    }

    // ============================================================
    // API 响应组装
    // ============================================================

    /**
     * 转为 API Map — 与 SkillService.listAllSkills() 响应格式一致。
     */
    public Map<String, Object> toApiMap(Skill org) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", org.getId().toString());
        item.put("type", "organization");
        item.put("companyId", org.getCompanyId().toString());
        item.put("displayName", org.getDisplayName());
        item.put("ownerName", org.getDisplayName());
        item.put("ownerTitle", org.getDescription() != null ? org.getDescription() : "");
        item.put("avatarUrl", org.getAvatarUrl());
        item.put("status", org.getStatus());
        item.put("domain", org.getDomain());
        item.put("openingMessage", org.getOpeningMessage());
        item.put("introProfile", JsonUtil.parseStringMap(org.getIntroProfile()));
        item.put("tags", List.of());
        item.put("memberCount", resolveMembers(org).size());
        item.put("grainCount", 0); // 组织分身无自有颗粒，前端用 memberCount 替代展示

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("conversationCount", org.getConversationCount() != null ? org.getConversationCount() : 0);
        stats.put("userCount", org.getUserCount() != null ? org.getUserCount() : 0);
        stats.put("satisfactionRate", org.getSatisfactionRate() != null ? org.getSatisfactionRate() : 0);
        if (org.getLastActiveAt() != null) {
            stats.put("lastActive", org.getLastActiveAt().toString());
        }
        item.put("stats", stats);
        return item;
    }

    /**
     * 获取组织分身详情 — 含完整成员列表 + 变动感知。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getDetail(UUID id) {
        Skill org = findById(id);
        Map<String, Object> detail = toApiMap(org);

        List<UUID> allMemberIds = JsonUtil.parseList(org.getMemberSkillIds(), UUID::fromString);
        List<Skill> activeMembers = resolveMembers(org);
        List<UUID> activeIds = activeMembers.stream().map(Skill::getId).collect(Collectors.toList());

        // 活跃成员
        List<Map<String, Object>> memberList = activeMembers.stream().map(m -> {
            Map<String, Object> mInfo = new LinkedHashMap<>();
            mInfo.put("id", m.getId().toString());
            mInfo.put("displayName", m.getDisplayName());
            mInfo.put("ownerName", m.getOwnerName());
            mInfo.put("ownerTitle", m.getOwnerTitle());
            mInfo.put("avatarUrl", m.getAvatarUrl());
            mInfo.put("department", m.getDepartment());
            mInfo.put("domain", m.getDomain());
            mInfo.put("conversationCount", m.getConversationCount() != null ? m.getConversationCount() : 0);
            return mInfo;
        }).collect(Collectors.toList());
        detail.put("members", memberList);

        // 已下线成员（变动感知）
        List<UUID> inactiveIds = allMemberIds.stream()
                .filter(mid -> !activeIds.contains(mid))
                .collect(Collectors.toList());
        if (!inactiveIds.isEmpty()) {
            List<Skill> inactiveSkills = skillRepository.findAllById(inactiveIds);
            List<Map<String, Object>> inactiveList = inactiveSkills.stream().map(m -> {
                Map<String, Object> mInfo = new LinkedHashMap<>();
                mInfo.put("id", m.getId().toString());
                mInfo.put("ownerName", m.getOwnerName() != null ? m.getOwnerName() : "未知");
                mInfo.put("displayName", m.getDisplayName());
                mInfo.put("status", m.getStatus());
                return mInfo;
            }).collect(Collectors.toList());
            detail.put("inactiveMembers", inactiveList);
        }

        // sceneTags — 聚合所有成员空间的场景标签（复用已解析的 activeMembers，避免重复 findAllById）
        List<UUID> memberSpaceIds = activeMembers.stream()
                .map(Skill::getSpaceId).distinct().collect(Collectors.toList());
        if (!memberSpaceIds.isEmpty()) {
            detail.put("sceneTags", aggregateSceneTags(memberSpaceIds));
        } else {
            detail.put("sceneTags", List.of());
        }

        return detail;
    }

    // ============================================================
    // 对练场景 — 聚合所有成员的 scene_tag，按覆盖成员数排序
    // ============================================================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPracticeScenes(String orgSkillId) {
        UUID id = UUID.fromString(orgSkillId);
        Skill org = findById(id);
        List<UUID> memberSpaceIds = resolveMemberSpaceIds(org);
        if (memberSpaceIds.isEmpty()) return List.of();

        // 批量查询：一次 DB 调用拿到所有 (spaceId, sceneTag) 去重对，替代 N 次 findBySpaceId
        List<Object[]> rows = grainRepository.findDistinctSceneTagsBySpaceIdIn(memberSpaceIds);
        Map<String, Set<UUID>> sceneCoverage = new LinkedHashMap<>();
        for (Object[] row : rows) {
            UUID sid = (UUID) row[0];
            String tag = (String) row[1];
            sceneCoverage.computeIfAbsent(tag, k -> new java.util.LinkedHashSet<>()).add(sid);
        }

        return sceneCoverage.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .map(entry -> {
                    Map<String, Object> scene = new LinkedHashMap<>();
                    scene.put("label", entry.getKey());
                    scene.put("title", entry.getKey());
                    scene.put("memberCount", entry.getValue().size());
                    scene.put("totalMembers", memberSpaceIds.size());
                    return scene;
                })
                .collect(Collectors.toList());
    }

    // ============================================================
    // 场景标签 — 聚合所有成员的 scene_tag
    // ============================================================

    /**
     * 聚合多空间的场景标签，格式与 SkillService.getSceneTags 一致。
     * <p>使用批量 IN 查询（2 次 DB 调用），替代 N 次单空间查询。</p>
     *
     * @param spaceIds 成员空间 ID 列表
     * @return [{tag, count, description}, ...]
     */
    private List<Map<String, Object>> aggregateSceneTags(List<UUID> spaceIds) {
        if (spaceIds.isEmpty()) return List.of();

        // 1 次批量查询：每场景颗粒计数
        List<Object[]> grainCounts = grainRepository.countGrainsBySceneInSpaceIds(spaceIds);
        // 1 次批量查询：每场景最佳颗粒描述
        List<Object[]> bestGrains = grainRepository.findBestGrainsPerSceneInSpaceIds(spaceIds);
        Map<String, String> descMap = bestGrains.stream()
                .collect(Collectors.toMap(
                        row -> (String) row[0], row -> (String) row[1], (a, b) -> a));

        List<Map<String, Object>> tags = new ArrayList<>();
        for (Object[] row : grainCounts) {
            String tagName = (String) row[0];
            long count = (Long) row[1];
            String desc = descMap.getOrDefault(tagName, tagName + "相关经验");

            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("tag", tagName);
            tag.put("count", (int) count);
            tag.put("description", desc.length() > 30 ? desc.substring(0, 30) + "..." : desc);
            tags.add(tag);
        }
        return tags;
    }

    /**
     * 获取组织分身的场景标签 — 供 C 端 scene-tags API 调用。
     *
     * @param orgSkillId 组织分身 ID
     * @return [{tag, count, description}, ...]
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSceneTags(String orgSkillId) {
        UUID id = UUID.fromString(orgSkillId);
        Skill org = findById(id);
        List<UUID> spaceIds = resolveMemberSpaceIds(org);
        return aggregateSceneTags(spaceIds);
    }

    private String toJson(List<UUID> ids) {
        try {
            return objectMapper.writeValueAsString(ids != null ? ids : List.of());
        } catch (Exception e) {
            log.warn("序列化 memberSkillIds 失败", e);
            return "[]";
        }
    }

    /**
     * 组织分身推荐问题 fallback — 聚合所有成员空间的场景标签，模板生成推荐问题。
     * 仅当 SkillController 缓存未命中时调用。
     */
    public List<String> getRecommendedQuestionsFallback(UUID orgSkillId) {
        try {
            Skill org = findById(orgSkillId);
            List<UUID> spaceIds = resolveMemberSpaceIds(org);
            java.util.Set<String> all = new java.util.LinkedHashSet<>();
            for (UUID sid : spaceIds) {
                grainRepository.findBySpaceId(sid).stream()
                    .filter(g -> g.getSceneTag() != null && "active".equals(g.getStatus()))
                    .map(g -> g.getSceneTag()).distinct()
                    .forEach(tag -> all.addAll(practiceDemoService.generateRecommendedQuestions(tag)));
            }
            return new ArrayList<>(all);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    // ============================================================
    // 溯源链接 — spaceId → skillId 映射
    // ============================================================

    /** 构建 spaceId → skillId 映射，用于前端溯源卡片的成员可点击跳转 */
    public Map<UUID, UUID> resolveSpaceToSkillMap(Skill org) {
        return resolveMembers(org).stream()
                .collect(java.util.stream.Collectors.toMap(
                        Skill::getSpaceId, Skill::getId, (a, b) -> a));
    }

    // ============================================================
    // 价值面板 — MVP：只读已有统计列
    // ============================================================

    @Transactional(readOnly = true)
    public Map<String, Object> getDashboard(UUID orgId) {
        Skill org = findById(orgId);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", org.getDisplayName());
        d.put("status", org.getStatus());
        d.put("conversationCount", org.getConversationCount() != null ? org.getConversationCount() : 0);
        d.put("userCount", org.getUserCount() != null ? org.getUserCount() : 0);
        d.put("satisfactionRate", org.getSatisfactionRate() != null ? org.getSatisfactionRate() : 0);
        d.put("memberCount", resolveMembers(org).size());
        d.put("activeMembers", resolveMembers(org).stream().map(m -> {
            Map<String, Object> mInfo = new LinkedHashMap<>();
            mInfo.put("id", m.getId().toString());
            mInfo.put("ownerName", m.getOwnerName());
            mInfo.put("avatarUrl", m.getAvatarUrl());
            mInfo.put("ownerTitle", m.getOwnerTitle());
            mInfo.put("conversationCount", m.getConversationCount() != null ? m.getConversationCount() : 0);
            return mInfo;
        }).collect(java.util.stream.Collectors.toList()));
        if (org.getLastActiveAt() != null) d.put("lastActiveAt", org.getLastActiveAt().toString());
        return d;
    }

}
