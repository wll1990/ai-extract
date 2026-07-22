package com.aiextract.service;

import com.aiextract.config.DomainConfigLoader;
import com.aiextract.config.PromptLoader;
import com.aiextract.dto.PracticeRespondRequest;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Report;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillProfile;
import com.aiextract.model.Space;
import com.aiextract.repository.SkillMessageRepository;
import com.aiextract.repository.SkillProfileRepository;
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
    private final ObjectMapper objectMapper;

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
                String tier = grainTiers.getOrDefault(entry.getValue().get(0).getId(), null);
                if ("high".equals(tier)) {
                    expCtx.append("## 【核心经验 · 高度匹配当前问题】").append(entry.getKey()).append("\n");
                } else if ("ref".equals(tier)) {
                    expCtx.append("## 【参考经验】").append(entry.getKey()).append("\n");
                } else {
                    expCtx.append("## ").append(entry.getKey()).append("\n");
                }
                for (ExperienceGrain g : entry.getValue()) {
                    if (g.getExpertThought() != null) expCtx.append("- 我的思考：").append(truncate(g.getExpertThought(), 200)).append("\n");
                    if (g.getStandardScript() != null) expCtx.append("- 我说过的话：\"").append(truncate(g.getStandardScript(), 300)).append("\"\n");
                    if (g.getCommonMistakes() != null) expCtx.append("- 我见过的坑：").append(truncate(g.getCommonMistakes(), 150)).append("\n");
                }
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

        // boundary_rules: 无颗粒时注入诚实边界规则
        String boundaryRules = "";
        if (grouped.isEmpty()) {
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

            boundaryRules = "## 最高优先级边界\n\n"
                + "本分身当前缺少关于此问题的经验数据。你必须：\n"
                + "1. 第一句话诚实告知'我没有完整的实战经验，不敢乱给建议'\n"
                + "2. 绝不基于直觉编造案例或话术\n"
                + "3. 用 2-3 句话引向你擅长的方向：" + sceneGuide + "\n"
                + "4. 语气温暖自然，用'说不定能帮到你'而非'请换个问题'\n";
        }
        params.put("boundary_rules", boundaryRules);

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
        return messages;
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
