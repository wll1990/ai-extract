package com.aiextract.service;

import com.aiextract.config.PromptLoader;
import com.aiextract.dto.FeedbackRequest;
import com.aiextract.dto.PracticeRespondRequest;
import com.aiextract.dto.PracticeStartRequest;
import com.aiextract.dto.PracticeStartResponse;
import com.aiextract.dto.SkillChatRequest;
import com.aiextract.common.ErrorMessages;
import com.aiextract.common.TraceContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AppUser;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Report;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillProfile;
import com.aiextract.model.Space;
import com.aiextract.model.User;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.ReportRepository;
import com.aiextract.repository.SkillProfileRepository;
import com.aiextract.repository.SkillEvaluationRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

import com.aiextract.config.DomainConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * AI分身Skill服务
 *
 * <p>提供分身问答（三种模式）、对练场景管理和反馈收集功能。
 * 核心依赖experience_grain表中的经验颗粒来驱动AI回答。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    private final SkillRepository skillRepository;
    private final ExperienceGrainRepository grainRepository;
    private final SkillProfileRepository profileRepository;
    private final GrainRetriever grainRetriever;
    private final ReportRepository reportRepository;
    private final SpaceRepository spaceRepository;
    private final UserRepository userRepository;
    private final ChatStreamAdapter chatStreamAdapter;
    private final com.aiextract.config.DomainConfigLoader domainConfigLoader;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final com.aiextract.repository.SkillConversationRepository conversationRepository;
    private final com.aiextract.repository.SkillMessageRepository skillMessageRepository;
    private final PracticeDemoService practiceDemoService;
    private final com.aiextract.repository.FeedbackLogRepository feedbackLogRepository;
    private final SkillEvaluationRepository skillEvaluationRepository;
    @org.springframework.beans.factory.annotation.Value("${storage.local.path:}")
    private String storageBasePath;

    // ==================== Helper methods（ChatStreamService 复用） ====================

    /**
     * 简单相关性评分（基于关键词匹配，后续可升级为向量语义检索）
     */
    int relevanceScore(ExperienceGrain grain, String query) {
        int score = 0;
        String q = query.toLowerCase();
        if (grain.getSceneTag() != null && grain.getSceneTag().toLowerCase().contains(q)) score += 5;
        if (grain.getSceneDescription() != null && grain.getSceneDescription().toLowerCase().contains(q)) score += 3;
        if (grain.getExpertThought() != null && grain.getExpertThought().toLowerCase().contains(q)) score += 4;
        if (grain.getStandardScript() != null && grain.getStandardScript().toLowerCase().contains(q)) score += 2;
        // 单字拆分匹配
        for (String word : q.split("")) {
            if (word.length() <= 1) continue;
            if (grain.getSceneDescription() != null && grain.getSceneDescription().contains(word)) score += 1;
            if (grain.getExpertThought() != null && grain.getExpertThought().contains(word)) score += 1;
        }
        return score;
    }

    /**
     * 开始实战对练
     *
     * @param skillId Skill ID
     * @param request 场景选择（scene或customScene）
     * @return 场景设定、客户首句台词、练习角度
     */
    public PracticeStartResponse startPractice(String skillId, PracticeStartRequest request, UUID userId) {
        UUID id = UUID.fromString(skillId);
        Skill skill = skillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SKILL_NOT_FOUND));

        String practiceId = UUID.randomUUID().toString().substring(0, 8);

        PracticeStartResponse.PracticeSceneInfo sceneInfo;
        List<String> angles;
        if (request.getCustomScene() != null && !request.getCustomScene().isEmpty()) {
            sceneInfo = PracticeStartResponse.PracticeSceneInfo.builder()
                    .title("自定义场景")
                    .setting("自定义对练场景")
                    .customerLine(request.getCustomScene())
                    .build();
            angles = practiceDemoService.getScenePracticeAngles(id, "通用");
        } else if (request.getScene() != null && !request.getScene().isBlank()) {
            // 使用指定的场景标签 — 从颗粒中加载场景信息
            sceneInfo = loadSceneByTag(id, skill.getSpaceId(), request.getScene());
            angles = buildPracticeAngles(request.getScene());
        } else {
            // 默认选第一个有活跃颗粒的场景
            String defaultScene = grainRepository.findBySpaceId(skill.getSpaceId()).stream()
                    .filter(g -> "active".equals(g.getStatus()) && g.getSceneTag() != null)
                    .findFirst().map(ExperienceGrain::getSceneTag).orElse("通用");
            sceneInfo = loadSceneByTag(id, skill.getSpaceId(), defaultScene);
            angles = practiceDemoService.getScenePracticeAngles(id, defaultScene);
        }

        // 持久化：已发布分身创建 Conversation + 首条客户消息
        String conversationId = null;
        if ("published".equals(skill.getStatus())) {
            var now = LocalDateTime.now();
            String title = request.getScene() != null ? request.getScene()
                    : request.getCustomScene() != null && !request.getCustomScene().isEmpty()
                    ? "自定义对练" : "对练";
            var conv = conversationRepository.save(com.aiextract.model.SkillConversation.builder()
                    .id(UUID.randomUUID()).skillId(id).userId(userId)
                    .title(title).mode("practice")
                    .createdAt(now).updatedAt(now).build());
            conversationId = conv.getId().toString();
            skillMessageRepository.save(com.aiextract.model.SkillMessage.builder()
                    .id(UUID.randomUUID()).conversationId(conv.getId())
                    .role("assistant").roleLabel("客户")
                    .content(sceneInfo.getCustomerLine()).createdAt(now).build());
        }

        log.info("对练开始, practiceId: {}, skillId: {}, scene: {}, angles: {}, convId: {}",
                practiceId, skillId, sceneInfo.getTitle(), angles.size(), conversationId);

        return PracticeStartResponse.builder()
                .practiceId(practiceId)
                .conversationId(conversationId)
                .scene(sceneInfo)
                .practiceAngles(angles)
                .totalAngles(angles.size())
                .build();
    }

    /**
     * 提交回答反馈
     *
     * @param skillId Skill ID
     * @param request 反馈（sessionId/grainId/helpful）
     */
    /**
     * 提交用户反馈。增强版：
     * ① 写 feedback_log（含完整上下文：提问/AI回答/RAG分数）
     * ② 有 grain_id 时才更新计数器（向后兼容现有统计）
     * ③ grain_id 允许 NULL——RAG 无匹配时用户仍可表达满意度
     */
    @Transactional(rollbackFor = Exception.class)
    public void submitFeedback(String skillId, FeedbackRequest request) {
        UUID skillUuid = UUID.fromString(skillId);
        UUID grainId = null;
        if (request.getGrainId() != null && !request.getGrainId().isBlank()) {
            grainId = UUID.fromString(request.getGrainId());
        }

        // ① 写入反馈日志（每次打分一条，无论有无 grain_id）
        feedbackLogRepository.save(com.aiextract.model.FeedbackLog.builder()
            .id(UUID.randomUUID())
            .skillId(skillUuid)
            .conversationId(request.getConversationId() != null
                ? UUID.fromString(request.getConversationId()) : null)
            .grainId(grainId)
            .rating(Boolean.TRUE.equals(request.getHelpful()) ? "up" : "down")
            .query(request.getQuery())
            .aiResponse(request.getAiResponse())
            .ragScore(request.getRagScore())
            .source("user")
            .createdAt(LocalDateTime.now())
            .build());

        // ② 有 grain_id 时才更新颗粒计数器（保持向后兼容）
        if (grainId != null) {
            if (Boolean.TRUE.equals(request.getHelpful())) {
                grainRepository.incrementHelpful(grainId);
            } else {
                grainRepository.incrementUnhelpful(grainId);
            }
        }
        log.info("反馈已记录 skillId={} grainId={} rating={}", skillId, grainId,
            Boolean.TRUE.equals(request.getHelpful()) ? "up" : "down");
    }

    /**
     * 获取所有分身列表（含所有者信息）
     */

    // ========== 会话历史管理 ==========

    /**
     * 会话属主校验 — 对外开放后所有按 convId 的读/删/写都必须先过此闸。
     *
     * <p>规则：会话属主本人可访问；B 端 super_admin 豁免（后台运营需要）。
     * C 端用户（app_user）在企业 user 表中查不到，自然只能访问自己的会话。</p>
     *
     * @throws BusinessException 404 会话不存在 / 403 无权访问
     */
    // ========== 会话管理 — 已迁移到 ConversationService ==========

    // ========== 分身列表 ==========

    private final com.aiextract.repository.AppUserRepository appUserRepository;

    public Map<String, Object> listAllSkills(int page, int size, String status, UUID userId,
                                              UUID companyId, String role) {
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(page - 1, size);
        org.springframework.data.domain.Page<Skill> skillPage;

        boolean isSuperAdmin = "super_admin".equalsIgnoreCase(role);
        boolean isCEnd = "c_user".equalsIgnoreCase(role);

        if (userId != null) {
            // 显式传了 userId → "我的分身"，所有角色（含 super_admin）都按 userId 过滤
            List<UUID> userSpaceIds = spaceRepository.findByUserId(userId).stream()
                    .map(Space::getId).toList();
            if (userSpaceIds.isEmpty()) return emptyPage(page, size);
            if (status != null && !status.isEmpty()) {
                skillPage = skillRepository.findBySpaceIdInAndStatusOrderByCreatedAtDesc(
                        userSpaceIds, status, pageable);
            } else {
                skillPage = skillRepository.findBySpaceIdIn(userSpaceIds, pageable);
            }
        } else if (isSuperAdmin) {
            // super_admin 且未指定 userId → 看全量
            if (status != null && !status.isEmpty()) {
                skillPage = skillRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
            } else {
                skillPage = skillRepository.findAll(pageable);
            }
        } else if (isCEnd) {
            // C端 → 只查自己的 space 下的分身
            List<UUID> userSpaceIds = spaceRepository.findByUserId(userId).stream()
                .map(Space::getId).toList();
            if (userSpaceIds.isEmpty()) return emptyPage(page, size);
            if (status != null && !status.isEmpty()) {
                skillPage = skillRepository.findBySpaceIdInAndStatusOrderByCreatedAtDesc(
                    userSpaceIds, status, pageable);
            } else {
                skillPage = skillRepository.findBySpaceIdIn(userSpaceIds, pageable);
            }
        } else if (companyId != null) {
            // B端按 company 过滤：本公司所有员工的 space → skill
            // Partner 是独立企业，不混入 B 端企业列表
            List<UUID> companyUserIds = userRepository.findByCompanyId(companyId).stream()
                .map(User::getId).toList();
            if (companyUserIds.isEmpty()) return emptyPage(page, size);
            List<UUID> companySpaceIds = spaceRepository.findByUserIdIn(companyUserIds).stream()
                .map(Space::getId).toList();
            if (companySpaceIds.isEmpty()) return emptyPage(page, size);
            if (status != null && !status.isEmpty()) {
                skillPage = skillRepository.findBySpaceIdInAndStatusOrderByCreatedAtDesc(
                    companySpaceIds, status, pageable);
            } else {
                skillPage = skillRepository.findBySpaceIdIn(companySpaceIds, pageable);
            }
        } else if (status != null && !status.isEmpty()) {
            skillPage = skillRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        } else {
            skillPage = skillRepository.findAll(pageable);
        }
        List<Skill> allSkills = skillPage.getContent();

        // 批量查 space、user、grains，避免 N+1
        List<UUID> spaceIds = allSkills.stream().map(Skill::getSpaceId).distinct().toList();
        Map<UUID, Space> spaceMap = spaceRepository.findAllById(spaceIds).stream()
                .collect(Collectors.toMap(Space::getId, s -> s, (a, b) -> a));
        List<UUID> ownerIds = spaceMap.values().stream().map(Space::getUserId).distinct().toList();
        // 同时查 B 端 user 和 C 端 app_user
        Map<UUID, String> userNameMap = new HashMap<>();
        userRepository.findAllById(ownerIds).forEach(u -> userNameMap.put(u.getId(), u.getName()));
        appUserRepository.findAllById(ownerIds).forEach(u -> userNameMap.put(u.getId(), u.getNickname()));
        // 批量查活跃锦囊数
        Map<UUID, Long> grainCountMap = grainRepository.countBySpaceIdIn(spaceIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1], (a, b) -> a));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Skill skill : allSkills) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", skill.getId().toString());
            item.put("spaceId", skill.getSpaceId() != null ? skill.getSpaceId().toString() : null);
            item.put("status", skill.getStatus());
            item.put("modelName", skill.getModelName());
            Space sp = spaceMap.get(skill.getSpaceId());
            String ownerName = sp != null ? userNameMap.getOrDefault(sp.getUserId(), "未知") : "未知";
            item.put("ownerName", ownerName);
            item.put("displayName", skill.getDisplayName() != null ? skill.getDisplayName() : ownerName);
            item.put("ownerTitle", skill.getOwnerTitle() != null ? skill.getOwnerTitle()
                    : (sp != null && sp.getDescription() != null ? sp.getDescription() : ""));
            item.put("avatarUrl", skill.getAvatarUrl());
            item.put("tags", parseJsonList(skill.getTags()));
            item.put("openingMessage", skill.getOpeningMessage());
            item.put("domain", skill.getDomain());
            item.put("grainCount", grainCountMap.getOrDefault(skill.getSpaceId(), 0L).intValue());
            result.add(item);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", result);
        response.put("page", page); response.put("size", size);
        response.put("total", skillPage.getTotalElements());
        response.put("totalPages", skillPage.getTotalPages());
        return response;
    }

    private Map<String, Object> emptyPage(int page, int size) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", List.of());
        response.put("page", page); response.put("size", size);
        response.put("total", 0L);
        response.put("totalPages", 0);
        return response;
    }

    /**
     * 更新分身状态
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateSkillStatus(String skillId, String status) {
        Skill skill = skillRepository.findById(UUID.fromString(skillId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SKILL_NOT_FOUND));
        skill.setStatus(status);
        skillRepository.save(skill);
        log.info("分身状态已更新, skillId: {}, status: {}", skillId, status);
    }

    /**
     * 获取对练场景列表（从经验颗粒提取）
     *
     * <p>从experience_grain表按scene_tag分组，提取有足够信息的颗粒构建对练场景。
     * 替代前端的硬编码PRESET_SCENES。</p>
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPracticeScenes(String skillId) {
        UUID id = UUID.fromString(skillId);
        Skill skill = skillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SKILL_NOT_FOUND));

        // DB 层聚合：最佳颗粒 + 计数，替代 Java 内存 groupGrainsByScene
        UUID spaceId = skill.getSpaceId();
        List<Object[]> bestGrains = grainRepository.findBestGrainsPerScene(spaceId);
        List<Object[]> grainCounts = grainRepository.countGrainsByScene(spaceId);
        Map<String, Long> countMap = grainCounts.stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (String) row[0], row -> (Long) row[1], (a, b) -> a));

        List<Map<String, Object>> scenes = new ArrayList<>();
        for (Object[] row : bestGrains) {
            String sceneTag = (String) row[0];
            String sceneDescription = (String) row[1];
            String commonMistakes = (String) row[2];

            String setting = !sceneDescription.isEmpty() ? sceneDescription : sceneTag + "场景练习";
            String preGenerated = readPracticeOpening(id, sceneTag);
            String customerLine = preGenerated != null ? preGenerated
                    : "说实话，我对" + sceneTag + "这块还有些担心，你帮我分析分析？";
            long grainCount = countMap.getOrDefault(sceneTag, 0L);

            Map<String, Object> scene = new LinkedHashMap<>();
            scene.put("label", sceneTag);
            scene.put("title", setting.length() > 20 ? setting.substring(0, 20) + "..." : setting);
            scene.put("setting", setting);
            scene.put("customerLine", customerLine);
            scene.put("grainCount", (int) grainCount);
            scenes.add(scene);
        }
        return scenes;
    }

    /**
     * 获取分身的场景标签列表
     *
     * <p>从experience_grain表按scene_tag分组统计，返回该分身擅长的领域。
     * 用于前端开场区展示，替代硬编码标签。</p>
     *
     * @param skillId Skill ID
     * @return 场景标签列表（含经验条数）
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getSceneTags(String skillId) {
        UUID id = UUID.fromString(skillId);
        Skill skill = skillRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SKILL_NOT_FOUND));

        // DB 层聚合替代 Java 内存 groupGrainsByScene
        List<Object[]> grainCounts = grainRepository.countGrainsByScene(skill.getSpaceId());
        List<Object[]> bestGrains = grainRepository.findBestGrainsPerScene(skill.getSpaceId());
        Map<String, String> descMap = bestGrains.stream()
                .collect(java.util.stream.Collectors.toMap(
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
     * 获取销冠姓名
     */
    private String getOwnerName(UUID spaceId) {
        return spaceRepository.findById(spaceId)
                .flatMap(space -> userRepository.findById(space.getUserId()))
                .map(User::getName)
                .orElse("销冠");
    }

    // ==================== 私有方法 ====================

    /**
     * 解析对话模式
     */
    String resolveMode(String explicitMode, String message) {
        if (explicitMode != null) {
            return explicitMode;
        }
        if (message.startsWith("/对练") || message.startsWith("/练习")) return "practice";
        if (message.startsWith("/聊天") || message.startsWith("/talk")) return "talk";
        if (message.startsWith("/讨论") || message.startsWith("/聊聊")) return "discuss";
        if (message.matches(".*你怎么看|为什么|你觉得.*")) return "discuss";
        return "quick";
    }

    /**
     * 构建Skill System Prompt（人格化+追问引导）
     */
    String buildSkillSystemPrompt(Skill skill, List<ExperienceGrain> grains,
                                           String mode, String channel) {
        return buildSkillSystemPrompt(skill, grains, Map.of(), mode, channel);
    }

    /**
     * 构建 Skill System Prompt — 带颗粒质量分层标记
     *
     * @param grainTiers grainId → 质量标记: "high" / "ref" / null(不标注)
     */
    String buildSkillSystemPrompt(Skill skill, List<ExperienceGrain> grains,
            Map<UUID, String> grainTiers, String mode, String channel) {
        // 1. 加载画像
        SkillProfile profile = profileRepository.findBySkillId(skill.getId()).orElse(null);
        String ownerName = getOwnerName(skill.getSpaceId());
        Space space = spaceRepository.findById(skill.getSpaceId()).orElse(null);
        String ownerTitle = space != null && space.getDescription() != null ? space.getDescription() : "资深销冠";

        // 2. 构建经验上下文（RAG 检索结果，带相似度标记）
        StringBuilder expCtx = new StringBuilder();
        Map<String, List<ExperienceGrain>> grouped = groupGrainsByScene(grains);
        if (grouped.isEmpty()) {
            expCtx.append("（暂无结构化经验数据，但你依然可以基于自己的销售直觉来回答问题）\n");
        } else {
            for (Map.Entry<String, List<ExperienceGrain>> entry : grouped.entrySet()) {
                String tier = grainTiers.getOrDefault(entry.getValue().get(0).getId(), null);
                if ("high".equals(tier)) {
                    expCtx.append("## 【核心经验 · 高度匹配当前问题】").append(entry.getKey()).append("\n");
                } else if ("ref".equals(tier)) {
                    expCtx.append("## 【参考经验】").append(entry.getKey()).append("\n");
                } else {
                    expCtx.append("## ").append(entry.getKey()).append("\n");
                }
                for (ExperienceGrain g : entry.getValue()) {
                    if (g.getExpertThought() != null) expCtx.append("- 我的思考：").append(g.getExpertThought()).append("\n");
                    if (g.getStandardScript() != null) expCtx.append("- 我说过的话：\"").append(g.getStandardScript()).append("\"\n");
                    if (g.getCommonMistakes() != null) expCtx.append("- 我见过的坑：").append(g.getCommonMistakes()).append("\n");
                }
                expCtx.append("\n");
            }
        }

        // 3. 提取标签和技能信息
        String tags = parseJsonArray(skill.getTags());
        String scenarios = parseJsonArray(skill.getTargetScenarios());
        String domains = profile != null ? parseJsonArray(profile.getKnowledgeDomains()) : "";
        String commPrefs = profile != null ? parseJsonArray(profile.getCommunicationPreferences()) : "";

        // 4. 模式指令
        String modeInstruction = "";
        if ("quick".equals(mode)) {
            modeInstruction = "## 当前模式：快问快答\n"
                + "对方要直接能用的。根据他的问法调整回答侧重点：\n"
                + "- 问「怎么应对/步骤」→ 拆判断逻辑（信号→策略→话术），分1-2-3步\n"
                + "- 问「案例」→ 讲一个经验区里的真实故事，带客户背景和具体对话\n"
                + "- 问「话术」→ 给原话，标注每句话的用意\n"
                + "- 问「复盘/失败」→ 讲踩过的坑、为什么踩、怎么避免\n"
                + "引用经验区原话，不推销案例，不追问对方。";
        } else if ("discuss".equals(mode)) {
            modeInstruction = "## 当前模式：深度探讨\n"
                + "对方想学透。根据他的问法调整回答侧重点：\n"
                + "- 问「怎么应对/步骤」→ 深入拆判断逻辑，每种策略的选择依据\n"
                + "- 问「案例」→ 完整讲述真实案例，包括当时怎么判断、说了什么、结果怎样\n"
                + "- 问「话术」→ 原话+语境+每句的博弈原理\n"
                + "- 问「复盘/失败」→ 失败案例的完整复盘：当时怎么想的、哪里错了、后来怎么改的\n"
                + "最后一句总结可迁移的判断力。不编造案例。";
        } else if ("talk".equals(mode)) {
            modeInstruction = "";
        }

        String template = "talk".equals(mode) ? "skill_talk.md" : "skill_qa_chat.md";

        Map<String, String> params = new HashMap<>();
        params.put("owner_name", ownerName);
        params.put("owner_title", ownerTitle);
        params.put("personality", profile != null && profile.getPersonality() != null ? profile.getPersonality() : "");
        params.put("speaking_style", profile != null && profile.getSpeakingStyle() != null ? profile.getSpeakingStyle() : "");
        params.put("background", profile != null && profile.getBackground() != null ? profile.getBackground() : "");
        params.put("common_phrases", profile != null && profile.getCommonPhrases() != null ? profile.getCommonPhrases() : "");
        params.put("skill_tags", tags);
        params.put("target_scenarios", scenarios);
        params.put("knowledge_domains", domains);
        params.put("communication_preferences", commPrefs);
        params.put("experience_context", expCtx.toString());
        params.put("mode_instruction", modeInstruction);
        String domain = domainConfigLoader.resolveDomain(skill);
        return promptLoader.format(template, params, domain);
    }

    /**
     * 按场景标签分组经验颗粒
     */
    boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** 将JSONB数组字符串转为可读文本 */
    private String parseJsonArray(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return "";
        try {
            @SuppressWarnings("rawtypes")
            java.util.List list = objectMapper.readValue(json, List.class);
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (sb.length() > 0) sb.append("、");
                sb.append(item.toString());
            }
            return sb.toString();
        } catch (Exception e) { log.warn("JSONB解析失败", e); return ""; }
    }

    private Map<String, List<ExperienceGrain>> groupGrainsByScene(List<ExperienceGrain> grains) {
        Map<String, List<ExperienceGrain>> grouped = new LinkedHashMap<>();
        for (ExperienceGrain g : grains) {
            String tag = g.getSceneTag() != null ? g.getSceneTag() : "通用";
            grouped.computeIfAbsent(tag, k -> new ArrayList<>()).add(g);
        }
        return grouped;
    }

    /**
     * 构建对话消息列表（含历史上下文）
     */
    List<Map<String, String>> buildChatMessages(String systemPrompt, String currentMsg,
                                                          UUID conversationId, String frontendHistory) {
        List<Map<String, String>> messages = new ArrayList<>();

        // 1. system prompt
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // 2. 从DB加载最近10轮（20条）历史（已发布分身走这里）
        var historyMsgs = skillMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        int maxHistory = 20;
        int start = Math.max(0, historyMsgs.size() - maxHistory);
        for (int i = start; i < historyMsgs.size(); i++) {
            var m = historyMsgs.get(i);
            messages.add(Map.of("role", m.getRole(), "content",
                m.getContent() != null ? m.getContent() : ""));
        }

        // 3. 前端传入的对话历史（管理员审核阶段未发布分身走这里）
        if (historyMsgs.isEmpty() && frontendHistory != null && !frontendHistory.isBlank()
                && !frontendHistory.equals("（对话刚开始）")) {
            messages.add(Map.of("role", "assistant",
                "content", "以下是我们之前的对话历史，请基于此上下文回答当前问题：\n" + frontendHistory));
        }

        // 4. 当前用户消息
        messages.add(Map.of("role", "user", "content", currentMsg));
        return messages;
    }

    /**
     * 从报告加载预设对练场景
     */
    private PracticeStartResponse.PracticeSceneInfo loadPracticeScene(UUID spaceId) {
        // 查询该空间最新报告
        List<Report> reports = reportRepository.findBySpaceIdOrderByCreatedAtDesc(
                spaceId, org.springframework.data.domain.PageRequest.of(0, 1)).getContent();

        if (!reports.isEmpty()) {
            Report report = reports.get(0);
            // 从content_json解析practice_scene（简化）
            return PracticeStartResponse.PracticeSceneInfo.builder()
                    .title("预设对练场景")
                    .setting("基于《" + report.getTitle() + "》的真实案例场景")
                    .customerLine("已经有两家在谈，不考虑新的")
                    .build();
        }

        return PracticeStartResponse.PracticeSceneInfo.builder()
                .title("默认场景")
                .setting("第一次见银行客户")
                .customerLine("已经有两家在谈，不考虑新的")
                .build();
    }

    /**
     * 从指定场景标签加载对练场景信息
     */
    private PracticeStartResponse.PracticeSceneInfo loadSceneByTag(UUID skillId, UUID spaceId, String sceneTag) {
        List<ExperienceGrain> grains = grainRepository.findBySpaceId(spaceId).stream()
                .filter(g -> sceneTag.equals(g.getSceneTag()) && "active".equals(g.getStatus()))
                .sorted(Comparator.comparing(ExperienceGrain::getQualityScore,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .collect(Collectors.toList());

        // 优先读预生成开场白（与审核页 autoDemo 同源：generateCustomerOpening）
        String customerLine = readPracticeOpening(skillId, sceneTag);
        if (customerLine == null) {
            customerLine = "说实话，我对" + sceneTag + "这块还有些担心，你帮我分析分析？";
        }

        // 获取报告信息用于溯源
        String reportTitle = "";
        List<Report> reports = reportRepository.findBySpaceIdOrderByCreatedAtDesc(
                spaceId, org.springframework.data.domain.PageRequest.of(0, 1)).getContent();
        { if (!reports.isEmpty()) reportTitle = reports.get(0).getTitle(); }

        // setting: 给AI客户的角色扮演指南 + 溯源信息
        String setting = "你是正在考察供应商的采购方，对「" + sceneTag + "」方面有真实顾虑。"
                + "你的态度：既感兴趣又持保留，需要对方用专业能力说服你。"
                + "基于" + grains.size() + "条销冠真实经验。"
                + (reportTitle.isEmpty() ? "" : "【溯源参考】报告：《" + reportTitle + "》，场景：" + sceneTag);

        return PracticeStartResponse.PracticeSceneInfo.builder()
                .title(sceneTag)
                .setting(setting)
                .customerLine(customerLine)
                .build();
    }

    /**
     * 根据场景标签生成默认练习角度（结合场景特点）
     */
    private List<String> buildPracticeAngles(String sceneTag) {
        return List.of(
                "客户在「" + sceneTag + "」方面有真实顾虑，说话保留、试探你的专业性——考验你如何建立信任",
                "客户提到过去在「" + sceneTag + "」方面的不愉快经历——考验你如何理解痛点并展示差异化",
                "客户开始松动但仍犹豫，追问具体落地细节——考验你如何给出可执行的承诺并推进成交");
    }

    /**
     * 构建对练System Prompt — AI扮演客户（训练伙伴模式）
     *
     * <p>AI客户隐藏训练伙伴身份，通过真实客户行为引导练习。
     * 基于场景角度规划轮次，覆盖完所有角度后自然收尾。</p>
     */
    String buildPracticeSystemPrompt(Skill skill, PracticeRespondRequest request) {
        String sceneContext = request.getSceneContext() != null ? request.getSceneContext() : "销售对练场景";
        String history = request.getHistory() != null ? request.getHistory() : "（对话刚开始）";
        int roundNumber = request.getRoundNumber() > 0 ? request.getRoundNumber() : 1;
        int totalAngles = request.getTotalAngles() > 0 ? request.getTotalAngles() : 3;
        String practiceAngles = request.getPracticeAngles() != null ? request.getPracticeAngles()
                : "1. 客户提出核心顾虑，考验对方如何回应不确定性\n2. 客户分享过往不愉快经历，考验对方如何建立信任\n3. 客户追问执行细节，考验对方如何给出具体承诺";

        String domain = domainConfigLoader.resolveDomain(skill);
        return promptLoader.format("skill_practice_customer.md", Map.of(
                "scene_context", sceneContext,
                "practice_angles", practiceAngles,
                "round_number", String.valueOf(roundNumber),
                "total_angles", String.valueOf(totalAngles),
                "history", history,
                "last_message", request.getMessage()
        ), domain);
    }

    /**
     * 构建对练评价Prompt — 销冠复盘
     */
    String buildPracticeEvaluatePrompt(String ownerName, String conversationJson, String scene,
                                                List<Report> reports, String domain) {
        StringBuilder sourceCtx = new StringBuilder();
        if (!reports.isEmpty()) {
            Report r = reports.get(0);
            sourceCtx.append("reportId: ").append(r.getId()).append("\n");
            sourceCtx.append("reportTitle: ").append(r.getTitle()).append("\n");
        }
        return promptLoader.format("skill_practice_evaluate.md", Map.of(
                "owner_name", ownerName,
                "conversation", conversationJson,
                "scene", scene,
                "source_context", sourceCtx.toString()
        ), domain);
    }

    /**
     * 构建企业总调度System Prompt（含真实多销冠颗粒）
     */
    String buildEnterpriseSystemPrompt(String question, List<ExperienceGrain> grains, String domain) {
        StringBuilder expCtx = new StringBuilder();
        if (!grains.isEmpty()) {
            Map<UUID, List<ExperienceGrain>> bySpace = new LinkedHashMap<>();
            for (ExperienceGrain g : grains) {
                bySpace.computeIfAbsent(g.getSpaceId(), k -> new ArrayList<>()).add(g);
            }
            for (Map.Entry<UUID, List<ExperienceGrain>> entry : bySpace.entrySet()) {
                expCtx.append("## 销冠空间 ").append(entry.getKey().toString().substring(0, 8)).append("\n");
                for (ExperienceGrain g : entry.getValue()) {
                    if (g.getSceneTag() != null) expCtx.append("- 场景：").append(g.getSceneTag()).append("\n");
                    if (g.getExpertThought() != null) expCtx.append("  思考：").append(g.getExpertThought()).append("\n");
                    if (g.getStandardScript() != null) expCtx.append("  话术：\"").append(g.getStandardScript()).append("\"\n");
                    if (g.getCommonMistakes() != null) expCtx.append("  避坑：").append(g.getCommonMistakes()).append("\n");
                }
                expCtx.append("\n");
            }
        } else {
            expCtx.append("（暂无经验数据，基于通用销售逻辑回答）\n");
        }

        return promptLoader.format("enterprise_system.md", Map.of(
                "experience_context", expCtx.toString()
        ), domain);
    }

    /**
     * 生成推荐问题 —— 从空间覆盖度最高的场景中取颗粒的 scene_description 转为问题。
     * RAG 无匹配时返回给前端，让用户继续对话而非卡住。
     *
     * @param skill 分身对象
     * @return 3 个推荐问题（不足时用通用问题补全）
     */
    public List<String> generateSuggestedQuestions(Skill skill) {
        // 取覆盖度最高的 3 个场景：按颗粒数降序排序
        java.util.Map<String, Long> sceneCounts = grainRepository.findBySpaceId(skill.getSpaceId()).stream()
            .filter(g -> g.getSceneTag() != null && !g.getSceneTag().isEmpty())
            .collect(java.util.stream.Collectors.groupingBy(
                com.aiextract.model.ExperienceGrain::getSceneTag,
                java.util.stream.Collectors.counting()));
        List<String> topScenes = sceneCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(3)
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toList());

        List<String> questions = new ArrayList<>();
        for (String scene : topScenes) {
            var grains = grainRepository.findBySpaceIdAndSceneTagAndStatus(
                skill.getSpaceId(), scene, "active");
            if (!grains.isEmpty()) {
                String desc = grains.get(0).getSceneDescription();
                if (desc != null && desc.length() > 5) {
                    // 转为问句格式
                    questions.add(desc.endsWith("？") || desc.endsWith("?") ? desc : "如何" + desc + "？");
                }
            }
        }
        // 不足 3 个时用通用问题兜底
        if (questions.size() < 3) {
            if (questions.stream().noneMatch(q -> q.contains("最成功的案例"))) {
                questions.add("能分享一个你最成功的案例吗？");
            }
            if (questions.stream().noneMatch(q -> q.contains("拒绝"))) {
                questions.add("遇到客户拒绝时，你会怎么处理？");
            }
        }
        return questions.stream().limit(3).collect(java.util.stream.Collectors.toList());
    }

    /**
     * 异步生成分身开场白 — 发布时触发，不阻塞 HTTP 响应。
     *
     * <p>使用 AI 根据分身画像生成一句有质感的自我介绍（15-30 字），
     * 保存到 skill.openingMessage。若已有手动填写的值则跳过。</p>
     */
    @Async("embeddingExecutor")
    public void generateOpeningMessage(UUID skillId) {
        try {
            Skill skill = skillRepository.findById(skillId).orElse(null);
            if (skill == null) return;

            // 已手动填写则跳过，不覆盖
            if (skill.getOpeningMessage() != null && !skill.getOpeningMessage().isBlank()) {
                log.info("开场白已手动填写，跳过自动生成 skillId={}", skillId);
                return;
            }

            // 收集 top 3 场景标签
            List<ExperienceGrain> grains = grainRepository.findBySpaceId(skill.getSpaceId());
            String scenes = grains.stream()
                .filter(g -> g.getSceneTag() != null && "active".equals(g.getStatus()))
                .collect(Collectors.groupingBy(ExperienceGrain::getSceneTag, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining("、"));
            if (scenes.isEmpty()) scenes = "通用销售";

            // 解析 tags JSONB
            List<String> tagList = parseJsonList(skill.getTags());
            String tags = tagList.isEmpty() ? (skill.getDomain() != null ? skill.getDomain() : "销售") : String.join("、", tagList);

            // 领域名称
            String domainId = domainConfigLoader.resolveDomain(skill);
            DomainConfig dc = domainId != null ? domainConfigLoader.load(domainId) : null;
            String domainName = dc != null && dc.getDomain() != null ? dc.getDomain().getName() : "销售";
            String roleLabel = dc != null && dc.getDomain() != null ? dc.getDomain().getRoleLabel() : "销冠";

            // 构建 prompt
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("owner_name", Objects.requireNonNullElse(skill.getOwnerName(), "销冠"));
            vars.put("owner_title", Objects.requireNonNullElse(skill.getOwnerTitle(), ""));
            vars.put("department", Objects.requireNonNullElse(skill.getDepartment(), ""));
            vars.put("seniority", Objects.requireNonNullElse(skill.getSeniority(), ""));
            vars.put("tags", tags);
            vars.put("scenes", scenes);
            vars.put("domain_name", domainName);

            String prompt = promptLoader.format("skill_opening_message.md", vars);
            String generated = chatStreamAdapter.chat(prompt);

            if (generated != null && !generated.isBlank()) {
                // 清理：去引号/换行，截断
                generated = generated.trim()
                    .replaceAll("^[\"'“”‘’]", "")
                    .replaceAll("[\"'“”‘’]$", "")
                    .replaceAll("\\n", "");
                if (generated.length() > 100) {
                    generated = generated.substring(0, 100);
                }
                skill.setOpeningMessage(generated);
                skillRepository.save(skill);
                log.info("开场白已生成 skillId={} chars={}", skillId, generated.length());
            }
        } catch (Exception e) {
            log.error("开场白生成失败 skillId={}", skillId, e);
            // 静默失败，不阻塞发布流程
        }
    }

    /**
     * 异步预生成各场景的对练客户开场白，存到 skill.practice_openings (JSON)。
     * 发布时触发，HTTP 路径直接读缓存，不调 AI。
     */
    @Async
    public void generatePracticeOpenings(UUID skillId) {
        try {
            Skill skill = skillRepository.findById(skillId).orElse(null);
            if (skill == null) return;

            // 用 DB 聚合查询获取所有活跃场景
            List<Object[]> scenes = grainRepository.countGrainsByScene(skill.getSpaceId());
            if (scenes.isEmpty()) {
                log.info("无活跃场景，跳过对练开场白生成 skillId={}", skillId);
                return;
            }

            Map<String, String> openings = new LinkedHashMap<>();
            // 已有缓存则增量更新，只补没有的场景
            if (skill.getPracticeOpenings() != null && !skill.getPracticeOpenings().isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, String> cached = objectMapper.readValue(skill.getPracticeOpenings(), Map.class);
                    openings.putAll(cached);
                } catch (Exception e) {
                    log.warn("practiceOpenings JSON 解析失败，重新生成 skillId={}", skillId);
                }
            }

            int generated = 0;
            for (Object[] row : scenes) {
                String sceneTag = (String) row[0];
                // 已有缓存则跳过
                if (openings.containsKey(sceneTag) && !openings.get(sceneTag).isBlank()) continue;

                try {
                    String opening = practiceDemoService.generateCustomerOpening(skillId, sceneTag);
                    if (opening != null && !opening.isBlank()) {
                        openings.put(sceneTag, opening.trim());
                        generated++;
                    }
                } catch (Exception e) {
                    log.warn("场景开场白生成失败 sceneTag={} skillId={}", sceneTag, skillId, e);
                }
            }

            if (generated > 0) {
                skill.setPracticeOpenings(objectMapper.writeValueAsString(openings));
                skillRepository.save(skill);
                log.info("对练开场白已生成 skillId={} generated={} total={}", skillId, generated, openings.size());
            }
        } catch (Exception e) {
            log.error("对练开场白批量生成失败 skillId={}", skillId, e);
        }
    }

    /**
     * 从 skill.practice_openings 读取指定场景的缓存开场白。
     * @return 缓存值，未命中返回 null
     */
    private String readPracticeOpening(UUID skillId, String sceneTag) {
        Skill skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null || skill.getPracticeOpenings() == null || skill.getPracticeOpenings().isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> map = objectMapper.readValue(skill.getPracticeOpenings(), Map.class);
            return map.get(sceneTag);
        } catch (Exception e) {
            return null;
        }
    }

    // ========== 分身详情（System B 聊天页入口） ==========

    /**
     * 获取分身完整详情 — 聊天页入场态所需全部信息。
     *
     * @param skillId 分身 ID
     * @return 头像、姓名、职级、开场白、场景标签、颗粒数、领域等
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getSkillDetail(String skillId) {
        Skill skill = skillRepository.findById(UUID.fromString(skillId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.SKILL_NOT_FOUND));

        // 场景标签
        List<ExperienceGrain> grains = grainRepository.findBySpaceId(skill.getSpaceId());
        Map<String, List<ExperienceGrain>> grouped = groupGrainsByScene(grains);
        List<Map<String, Object>> sceneTags = new ArrayList<>();
        for (Map.Entry<String, List<ExperienceGrain>> entry : grouped.entrySet()) {
            Map<String, Object> tag = new LinkedHashMap<>();
            tag.put("tag", entry.getKey());
            tag.put("count", entry.getValue().size());
            sceneTags.add(tag);
        }

        // 查 space 拿 owner 信息
        Space space = spaceRepository.findById(skill.getSpaceId()).orElse(null);
        String ownerName = skill.getOwnerName() != null ? skill.getOwnerName()
                : space != null ? userRepository.findById(space.getUserId()).map(User::getName).orElse("") : "";

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", skill.getId().toString());
        detail.put("displayName", skill.getDisplayName() != null ? skill.getDisplayName() : ownerName);
        detail.put("ownerName", ownerName);
        detail.put("ownerTitle", skill.getOwnerTitle() != null ? skill.getOwnerTitle()
                : (space != null && space.getDescription() != null ? space.getDescription() : ""));
        detail.put("avatarUrl", skill.getAvatarUrl());
        detail.put("department", skill.getDepartment());
        detail.put("tags", parseJsonList(skill.getTags()));
        detail.put("sceneTags", sceneTags);
        detail.put("grainCount", grains.stream().filter(g -> "active".equals(g.getStatus())).count());
        detail.put("openingMessage", skill.getOpeningMessage());
        detail.put("domain", skill.getDomain());
        detail.put("talkConfig", skill.getTalkConfig() != null ? skill.getTalkConfig() : "{}");
        detail.put("status", skill.getStatus());
        return detail;
    }

    /**
     * 将 JSONB 数组字符串转为 Java List（用于 API 返回）。
     */
    private static final String EMPTY_JSON_ARRAY = "[]";

    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank() || EMPTY_JSON_ARRAY.equals(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("JSONB parse failed", e);
            return List.of();
        }
    }

    /**
     * 安全发送SSE事件
     */

    /**
     * 查询对练评分趋势 — 返回用户在此分身下的历次对练评估分数。
     *
     * @param skillId 分身 ID
     * @param userId  用户 ID
     * @return 按时间排序的评分列表 [{score, styleScore, consistencyScore, behaviorScore, scriptReuseScore, createdAt}]
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPracticeScoreTrend(String skillId, UUID userId) {
        var evals = skillEvaluationRepository
            .findBySkillIdAndEvaluatorIdAndModeOrderByCreatedAtAsc(
                UUID.fromString(skillId), userId, "practice");
        return evals.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId().toString());
            m.put("score", e.getScore());
            m.put("styleScore", e.getStyleScore());
            m.put("consistencyScore", e.getConsistencyScore());
            m.put("behaviorScore", e.getBehaviorScore());
            m.put("scriptReuseScore", e.getScriptReuseScore());
            m.put("demoScript", e.getDemoScript());
            m.put("createdAt", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * 上传分身头像，保存文件并更新 skill.avatarUrl。
     */
    @Transactional
    public Map<String, String> uploadAvatar(String skillId, org.springframework.web.multipart.MultipartFile file) {
        Skill skill = skillRepository.findById(UUID.fromString(skillId))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), "分身不存在"));

        // 构建目标路径
        String basePath = storageBasePath != null && !storageBasePath.isBlank() ? storageBasePath : "data/files";
        String dir = basePath + "/avatars/" + skillId + "/";
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "avatar";
        String safeName = System.currentTimeMillis() + "_" + originalName.replaceAll("[^a-zA-Z0-9._\\-]", "_");

        java.io.File destDir = new java.io.File(dir).getAbsoluteFile();
        if (!destDir.exists()) destDir.mkdirs();
        java.io.File dest = new java.io.File(destDir, safeName);

        try {
            file.transferTo(dest);
        } catch (Exception e) {
            log.error("头像保存失败, skillId={}, path={}", skillId, dest.getAbsolutePath(), e);
            throw new RuntimeException("头像保存失败: " + e.getMessage());
        }

        // 写入相对路径作为 URL
        String avatarUrl = "/files/avatars/" + safeName;
        skill.setAvatarUrl(avatarUrl);
        skillRepository.save(skill);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("avatarUrl", avatarUrl);
        return result;
    }
}
