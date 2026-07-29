package com.aiextract.service;

import com.aiextract.config.DomainConfigLoader;
import com.aiextract.config.PromptLoader;
import com.aiextract.dto.PracticeRespondRequest;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.OrganizationSkill;
import com.aiextract.model.Report;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillProfile;
import com.aiextract.model.Space;
import com.aiextract.model.User;
import com.aiextract.repository.SkillMessageRepository;
import com.aiextract.repository.SkillProfileRepository;
import com.aiextract.repository.SkillRepository;
import com.aiextract.repository.SpaceRepository;
import com.aiextract.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * System Prompt 组装服务 — 拼接画像、经验、模式指令为完整 prompt。
 *
 * <p>从 {@link SkillService} 提取，职责聚焦于 Prompt 模板变量注入，
 * 不含 LLM 调用、RAG 检索或会话管理。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptAssemblyService {

    private final SkillProfileRepository profileRepository;
    private final SpaceRepository spaceRepository;
    private final UserRepository userRepository;
    private final DomainConfigLoader domainConfigLoader;
    private final PromptLoader promptLoader;
    private final SkillMessageRepository skillMessageRepository;
    private final com.aiextract.repository.ExperienceGrainRepository grainRepository;
    private final SkillRepository skillRepository;
    private final ObjectMapper objectMapper;
    private final ContextWindowGuard contextWindowGuard;

    // ============================================================
    // Skill System Prompt（QA / Talk / Quick / Discuss）
    // ============================================================

    /**
     * 构建 Skill System Prompt（人格化 + 经验上下文 + 模式指令）。
     */
    public String buildSkillSystemPrompt(Skill skill, List<ExperienceGrain> grains,
                                           String mode, String channel) {
        return buildSkillSystemPrompt(skill, grains, Map.of(), mode, channel);
    }

    /**
     * 构建 Skill System Prompt — 带颗粒质量分层标记。
     *
     * @param grainTiers grainId → 质量标记: "high" / "ref"
     */
    public String buildSkillSystemPrompt(Skill skill, List<ExperienceGrain> grains,
            Map<UUID, String> grainTiers, String mode, String channel) {
        return buildSkillSystemPrompt(skill, grains, grainTiers, Map.of(), mode, channel);
    }

    /**
     * 构建 Skill System Prompt — 带颗粒质量分层标记 + 相似度分数。
     *
     * @param grainSimilarities grainId → 相似度分数（0-1），用于 P1-10 匹配度标注
     */
    public String buildSkillSystemPrompt(Skill skill, List<ExperienceGrain> grains,
            Map<UUID, String> grainTiers, Map<UUID, Double> grainSimilarities,
            String mode, String channel) {
        SkillProfile profile = profileRepository.findBySkillId(skill.getId()).orElse(null);
        String ownerName = getOwnerName(skill.getSpaceId());
        Space space = spaceRepository.findById(skill.getSpaceId()).orElse(null);
        String ownerTitle = space != null && space.getDescription() != null ? space.getDescription() : "资深销冠";

        StringBuilder expCtx = new StringBuilder();
        Map<String, List<ExperienceGrain>> grouped = groupGrainsByScene(grains);
        if (grouped.isEmpty()) {
            expCtx.append("（暂无结构化经验数据，但你依然可以基于自己的销售直觉来回答问题）\n");
        } else {
            for (Map.Entry<String, List<ExperienceGrain>> entry : grouped.entrySet()) {
                // P1-4: 取组内最高 tier，而非第一个颗粒的 tier
                String tier = entry.getValue().stream()
                    .map(g -> grainTiers.getOrDefault(g.getId(), null))
                    .filter(t -> t != null)
                    .reduce((a, b) -> "high".equals(a) || "high".equals(b) ? "high"
                        : "ref".equals(a) || "ref".equals(b) ? "ref" : null)
                    .orElse(null);
                // P1-10: 用相似度百分比 + 颜色标记替代模糊标签
                UUID firstId = entry.getValue().get(0).getId();
                Double sim = grainSimilarities.getOrDefault(firstId,
                    grainSimilarities.isEmpty() ? null
                        : grainSimilarities.values().iterator().next());
                String simPct = sim != null ? String.format("%.0f%%", sim * 100) : "";
                String level = sim != null && sim >= 0.80 ? "🟢 高度匹配"
                    : sim != null && sim >= 0.50 ? "🟡 相关匹配"
                    : sim != null && sim >= 0.30 ? "🟠 弱相关"
                    : "🔴 仅供参考";
                expCtx.append("## 【").append(level);
                if (!simPct.isEmpty()) expCtx.append(" · ").append(simPct);
                expCtx.append("】").append(entry.getKey()).append("\n");
                for (ExperienceGrain g : entry.getValue()) {
                    if (g.getExpertThought() != null) expCtx.append("- 我的思考：").append(truncate(g.getExpertThought(), 200)).append("\n");
                    if (g.getStandardScript() != null) expCtx.append("- 我说过的话：\"").append(truncate(g.getStandardScript(), 300)).append("\"\n");
                    if (g.getCommonMistakes() != null) expCtx.append("- 我见过的坑：").append(truncate(g.getCommonMistakes(), 150)).append("\n");
                    // P1-3: applicableCondition 注入 prompt，让 LLM 判断场景是否匹配
                    if (g.getApplicableCondition() != null) expCtx.append("- ⚠️ 适用条件：").append(truncate(g.getApplicableCondition(), 100)).append("\n");
                }
                expCtx.append("\n");
            }

            // P0-6: Lost-in-the-Middle 缓解 — best grain 放尾部强调
            if (!grains.isEmpty() && !grainTiers.isEmpty()) {
                ExperienceGrain best = grains.stream()
                    .filter(g -> "high".equals(grainTiers.get(g.getId())))
                    .findFirst()
                    .orElse(grains.get(0));
                expCtx.append("## 💡 最匹配的经验（请优先参考）\n");
                if (best.getExpertThought() != null) expCtx.append("- 思考：").append(best.getExpertThought()).append("\n");
                if (best.getStandardScript() != null) expCtx.append("- 原话：\"").append(best.getStandardScript()).append("\"\n");
                expCtx.append("\n");
            }
        }

        String tags = parseJsonArray(skill.getTags());
        String scenarios = parseJsonArray(skill.getTargetScenarios());
        String domains = profile != null ? parseJsonArray(profile.getKnowledgeDomains()) : "";
        String commPrefs = profile != null ? parseJsonArray(profile.getCommunicationPreferences()) : "";

        String modeInstruction = buildModeInstruction(mode);

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

        // ── 两层边界管控：基础边界 ALWAYS 注入，升级边界按条件触发 ──

        // 基础边界 — 永远注入，覆盖所有其他指令
        String baseBoundary = """
            ## 最高优先级：回答边界（覆盖所有其他指令）

            ### 匹配度使用规则（每条经验标题旁有匹配度标识）

            - 🟢 匹配度 ≥ 80%：这项经验与你问的问题高度吻合。直接引用原话和策略，
              用第一人称讲述。这是你自己的真实经历。
            - 🟡 匹配度 50-80%：相关但不完全匹配。参考其中的思路，结合你的销售
              直觉来回答。不要逐字引用原话——用自己的话重新表达。
            - 🟠 匹配度 30-50%：仅作背景了解。不要把这当作核心答案。优先依靠你的
              销售直觉，只在自然相关时提及。如果不相关就忽略。
            - 🔴 匹配度 < 30%：视为不相关。忽略这条经验，不要引用。

            ### 通用铁律
            - 禁止创造经验区中没有的销售策略、谈判话术、解决方案
            - 禁止脱离经验区进行大范围推理、拓展、脑补
            - 可以在原话基础上做极小幅度语言润色，让表达更自然

            ### 当所有经验匹配度都低于 50% 时：
            - 第一句话诚实告知："这个问题我平时接触比较少，暂时没有成熟的应对思路"
            - 绝不基于直觉编造——宁可承认不会，也不给错的建议
            """;

        // 升级边界 — 无 high-tier 颗粒时额外注入
        String escalatedBoundary = "";
        long highTierCount = grainTiers.values().stream().filter("high"::equals).count();
        if (highTierCount == 0) {
            java.util.List<String> topScenes = grainRepository.findBySpaceId(skill.getSpaceId()).stream()
                .filter(g -> g.getSceneTag() != null && !g.getSceneTag().isEmpty())
                .collect(java.util.stream.Collectors.groupingBy(
                    com.aiextract.model.ExperienceGrain::getSceneTag,
                    java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(3)
                .map(java.util.Map.Entry::getKey)
                .toList();
            String sceneGuide = topScenes.isEmpty() ? "通用销售技巧"
                : String.join("、", topScenes);
            escalatedBoundary = String.format("""

                ## 当前情况：无高度匹配经验

                本分身当前缺少关于此问题的实战经验。你必须：
                1. 按基础边界中的规则，诚实告知
                2. 用 1-2 句话引向你擅长的方向："不过在%s方面，我有些实战经验，说不定能帮到你"
                3. 语气保持你自己的风格，温暖自然，不变成客服机器人
                """, sceneGuide);
        }

        params.put("boundary_rules", baseBoundary + escalatedBoundary);

        String domain = domainConfigLoader.resolveDomain(skill);
        return promptLoader.format(template, params, domain);
    }

    private String buildModeInstruction(String mode) {
        if ("quick".equals(mode)) {
            return "## 当前模式：快问快答\n"
                + "对方要直接能用的。根据他的问法调整回答侧重点：\n"
                + "- 问「怎么应对/步骤」→ 拆判断逻辑（信号→策略→话术），分1-2-3步\n"
                + "- 问「案例」→ 讲一个经验区里的真实故事，带客户背景和具体对话\n"
                + "- 问「话术」→ 给原话，标注每句话的用意\n"
                + "- 问「复盘/失败」→ 讲踩过的坑、为什么踩、怎么避免\n"
                + "引用经验区原话，不推销案例，不追问对方。";
        } else if ("discuss".equals(mode)) {
            return "## 当前模式：深度探讨\n"
                + "对方想学透。根据他的问法调整回答侧重点：\n"
                + "- 问「怎么应对/步骤」→ 深入拆判断逻辑，每种策略的选择依据\n"
                + "- 问「案例」→ 完整讲述真实案例，包括当时怎么判断、说了什么、结果怎样\n"
                + "- 问「话术」→ 原话+语境+每句的博弈原理\n"
                + "- 问「复盘/失败」→ 失败案例的完整复盘：当时怎么想的、哪里错了、后来怎么改的\n"
                + "最后一句总结可迁移的判断力。不编造案例。";
        }
        return "";
    }

    // ============================================================
    // Practice / Enterprise / ChatMessages
    // ============================================================

    /** 构建对练 System Prompt — AI 扮演客户。 */
    public String buildPracticeSystemPrompt(Skill skill, PracticeRespondRequest request) {
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

    /** 构建对练评价 Prompt — 销冠复盘。 */
    public String buildPracticeEvaluatePrompt(String ownerName, String conversationJson, String scene,
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

    /** 构建企业总调度 System Prompt（跨空间颗粒聚合）。 */
    public String buildEnterpriseSystemPrompt(String question, List<ExperienceGrain> grains, String domain) {
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
                    if (g.getExpertThought() != null) expCtx.append("  思考：").append(truncate(g.getExpertThought(), 200)).append("\n");
                    if (g.getStandardScript() != null) expCtx.append("  话术：\"").append(truncate(g.getStandardScript(), 300)).append("\"\n");
                    if (g.getCommonMistakes() != null) expCtx.append("  避坑：").append(truncate(g.getCommonMistakes(), 150)).append("\n");
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
     * 构建企业总调度 System Prompt V2 — 带 tier 标记 + 销冠姓名。
     * 替代旧版 buildEnterpriseSystemPrompt（仅按 space 分组，无姓名，无 tier）。
     *
     * @param question        用户问题
     * @param grains          检索到的颗粒
     * @param grainTiers      颗粒质量分层标记
     * @param companySpaceIds 公司所有 space（用于预加载 ownerName 映射）
     * @param domain          领域标识
     */
    public String buildEnterpriseSystemPromptV2(String question, List<ExperienceGrain> grains,
            Map<UUID, String> grainTiers, Map<UUID, Double> grainSimilarities,
            List<UUID> companySpaceIds, String domain) {
        // 预加载 space→ownerName 映射，避免逐个查
        Map<UUID, String> spaceOwnerMap = new HashMap<>();
        if (!grains.isEmpty()) {
            List<UUID> distinctSpaceIds = grains.stream()
                    .map(ExperienceGrain::getSpaceId).distinct().collect(Collectors.toList());
            List<Space> spaces = spaceRepository.findAllById(distinctSpaceIds);
            List<UUID> userIds = spaces.stream().map(Space::getUserId).distinct().collect(Collectors.toList());
            Map<UUID, String> userNames = userRepository.findAllById(userIds).stream()
                    .collect(Collectors.toMap(User::getId, User::getName, (a, b) -> a));
            for (Space sp : spaces) {
                spaceOwnerMap.put(sp.getId(),
                        userNames.getOrDefault(sp.getUserId(), "销冠"));
            }
        }

        StringBuilder expCtx = new StringBuilder();
        if (!grains.isEmpty()) {
            Map<UUID, List<ExperienceGrain>> bySpace = new LinkedHashMap<>();
            for (ExperienceGrain g : grains) {
                bySpace.computeIfAbsent(g.getSpaceId(), k -> new ArrayList<>()).add(g);
            }
            for (Map.Entry<UUID, List<ExperienceGrain>> entry : bySpace.entrySet()) {
                String ownerName = spaceOwnerMap.getOrDefault(entry.getKey(), "销冠");
                UUID firstId = entry.getValue().get(0).getId();
                Double sim = grainSimilarities.getOrDefault(firstId, null);
                String simPct = sim != null ? String.format(" · %.0f%%", sim * 100) : "";
                expCtx.append("## ").append(ownerName);
                String tier = grainTiers.getOrDefault(firstId, null);
                if ("high".equals(tier)) {
                    expCtx.append(" 🟢").append(simPct);
                } else if ("ref".equals(tier)) {
                    expCtx.append(" 🟡").append(simPct);
                }
                expCtx.append("\n");
                for (ExperienceGrain g : entry.getValue()) {
                    if (g.getSceneTag() != null)
                        expCtx.append("- 场景：").append(g.getSceneTag()).append("\n");
                    if (g.getExpertThought() != null)
                        expCtx.append("  思考：").append(truncate(g.getExpertThought(), 200)).append("\n");
                    if (g.getStandardScript() != null)
                        expCtx.append("  话术：\"").append(truncate(g.getStandardScript(), 300)).append("\"\n");
                    if (g.getCommonMistakes() != null)
                        expCtx.append("  避坑：").append(truncate(g.getCommonMistakes(), 150)).append("\n");
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
     * 构建组织分身 System Prompt — 按 mode 选择模板，按 spaceId 分组标注成员姓名。
     *
     * @param orgSkill   组织分身
     * @param grains     检索到的颗粒
     * @param grainTiers 颗粒质量分层标记
     * @param mode       聊天模式：qa / talk / practice
     * @param channel    渠道：web / h5 / feishu
     */
    public String buildOrgSkillSystemPrompt(OrganizationSkill orgSkill,
            List<ExperienceGrain> grains, Map<UUID, String> grainTiers,
            Map<UUID, Double> grainSimilarities, String mode, String channel) {

        // 批量预加载 space→memberName 映射，避免 N+1
        Map<UUID, String> memberNames = new HashMap<>();
        if (!grains.isEmpty()) {
            List<UUID> distinctSpaceIds = grains.stream()
                    .map(ExperienceGrain::getSpaceId).distinct().collect(Collectors.toList());
            // 批量查 Skill（orgSkill 成员的 ownerName 优先）
            List<Skill> memberSkills = skillRepository.findBySpaceIdIn(distinctSpaceIds);
            for (Skill sk : memberSkills) {
                if (sk.getOwnerName() != null) {
                    memberNames.put(sk.getSpaceId(), sk.getOwnerName());
                }
            }
            // 未命中 Skill 的 spaceId → 批量查 Space → User
            List<UUID> missingSpaceIds = distinctSpaceIds.stream()
                    .filter(sid -> !memberNames.containsKey(sid))
                    .collect(Collectors.toList());
            if (!missingSpaceIds.isEmpty()) {
                List<Space> spaces = spaceRepository.findAllById(missingSpaceIds);
                List<UUID> userIds = spaces.stream().map(Space::getUserId).distinct().collect(Collectors.toList());
                Map<UUID, String> userNames = userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getName, (a, b) -> a));
                for (Space sp : spaces) {
                    String name = userNames.getOrDefault(sp.getUserId(), "销冠");
                    memberNames.put(sp.getId(), name);
                }
            }
        }

        StringBuilder expCtx = new StringBuilder();
        if (!grains.isEmpty()) {
            Map<UUID, List<ExperienceGrain>> bySpace = new LinkedHashMap<>();
            for (ExperienceGrain g : grains) {
                bySpace.computeIfAbsent(g.getSpaceId(), k -> new ArrayList<>()).add(g);
            }
            for (Map.Entry<UUID, List<ExperienceGrain>> entry : bySpace.entrySet()) {
                String name = memberNames.getOrDefault(entry.getKey(), "未知销冠");
                UUID firstId = entry.getValue().get(0).getId();
                Double sim = grainSimilarities.getOrDefault(firstId, null);
                String simPct = sim != null ? String.format(" · %.0f%%", sim * 100) : "";
                expCtx.append("## ").append(name);
                String tier = grainTiers.getOrDefault(firstId, null);
                if ("high".equals(tier)) {
                    expCtx.append(" 🟢").append(simPct);
                } else if ("ref".equals(tier)) {
                    expCtx.append(" 🟡").append(simPct);
                }
                expCtx.append("\n");
                for (ExperienceGrain g : entry.getValue()) {
                    if (g.getSceneTag() != null)
                        expCtx.append("- 场景：").append(g.getSceneTag()).append("\n");
                    if (g.getExpertThought() != null)
                        expCtx.append("  思考：").append(truncate(g.getExpertThought(), 200)).append("\n");
                    if (g.getStandardScript() != null)
                        expCtx.append("  话术：\"").append(truncate(g.getStandardScript(), 300)).append("\"\n");
                    if (g.getCommonMistakes() != null)
                        expCtx.append("  避坑：").append(truncate(g.getCommonMistakes(), 150)).append("\n");
                }
                expCtx.append("\n");
            }
        } else {
            expCtx.append("（暂无结构化经验数据，但你依然可以基于团队共识来回答问题）\n");
        }

        String orgName = orgSkill.getName();
        String background = orgSkill.getDescription() != null ? orgSkill.getDescription() : "";

        String template = switch (mode) {
            case "talk" -> "org_skill_talk.md";
            case "practice" -> "org_skill_practice_customer.md";
            default -> "org_skill_qa.md";
        };

        String domain = orgSkill.getDomain() != null ? orgSkill.getDomain() : "sales";
        return promptLoader.format(template, Map.of(
                "org_name", orgName,
                "background", background,
                "experience_context", expCtx.toString()
        ), domain);
    }

    /** 构建对话消息列表（system + history + 当前消息）。 */
    public List<Map<String, String>> buildChatMessages(String systemPrompt, String currentMsg,
                                                          UUID conversationId, String frontendHistory) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        var historyMsgs = skillMessageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        int maxHistory = 20;
        int start = Math.max(0, historyMsgs.size() - maxHistory);
        for (int i = start; i < historyMsgs.size(); i++) {
            var m = historyMsgs.get(i);
            messages.add(Map.of("role", m.getRole(), "content",
                m.getContent() != null ? m.getContent() : ""));
        }

        if (historyMsgs.isEmpty() && frontendHistory != null && !frontendHistory.isBlank()
                && !frontendHistory.equals("（对话刚开始）")) {
            messages.add(Map.of("role", "assistant",
                "content", "以下是我们之前的对话历史，请基于此上下文回答当前问题：\n" + frontendHistory));
        }

        messages.add(Map.of("role", "user", "content", currentMsg));

        // P1-12: token 预检，超限自动裁剪最早消息（不含 system prompt）
        List<Map<String, String>> body = messages.subList(1, messages.size());
        body = contextWindowGuard.trimIfNeeded(body,
                systemPrompt != null ? systemPrompt.length() : 0);
        List<Map<String, String>> result = new ArrayList<>();
        result.add(Map.of("role", "system", "content", systemPrompt));
        result.addAll(body);
        return result;
    }

    // ============================================================
    // helpers
    // ============================================================

    /**
     * 从 Skill.spaceId 查 owner 姓名。
     */
    public String getOwnerName(UUID spaceId) {
        return spaceRepository.findById(spaceId)
                .flatMap(space -> userRepository.findById(space.getUserId()))
                .map(u -> u.getName())
                .orElse("销冠");
    }

    private String parseJsonArray(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) return "";
        try {
            @SuppressWarnings("rawtypes")
            List list = objectMapper.readValue(json, List.class);
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (sb.length() > 0) sb.append("、");
                sb.append(item.toString());
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("JSONB解析失败", e);
            return "";
        }
    }

    /** 截断超长文本，保留前 maxChars 字符，超长加 "…" */
    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "…";
    }

    private Map<String, List<ExperienceGrain>> groupGrainsByScene(List<ExperienceGrain> grains) {
        Map<String, List<ExperienceGrain>> grouped = new LinkedHashMap<>();
        for (ExperienceGrain g : grains) {
            String tag = g.getSceneTag() != null ? g.getSceneTag() : "通用";
            grouped.computeIfAbsent(tag, k -> new ArrayList<>()).add(g);
        }
        return grouped;
    }
}
