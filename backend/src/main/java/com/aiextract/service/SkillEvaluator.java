package com.aiextract.service;

import com.aiextract.config.PromptLoader;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Skill;
import com.aiextract.model.SkillEvaluation;
import com.aiextract.repository.ExperienceGrainRepository;
import com.aiextract.repository.SkillRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI Judge 四维评分
 *
 * 对分身对话表现进行四维评估：语言风格(30%)、经验一致性(30%)、
 * 行为模式(20%)、话术复用(20%)。
  * @author AI Extract Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillEvaluator {
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final com.aiextract.config.DomainConfigLoader domainConfigLoader;
    private final GrainRetriever grainRetriever;
    private final SkillRepository skillRepository;
    private final ExperienceGrainRepository grainRepository;
    private final ObjectMapper objectMapper;

    /**
     * 执行四维评分
     */
    public SkillEvaluation evaluate(UUID skillId, List<Map<String, String>> messages, String mode) {
        String aiResponse = extractAiResponses(messages);
        String question = extractLastUserQuestion(messages);
        UUID spaceId = getSpaceId(skillId);
        List<ExperienceGrain> topGrains = grainRetriever.retrieve(question, spaceId, 5);
        String realSamples = loadRealConversationSamples(skillId);

        String judgePrompt = buildJudgePrompt(aiResponse, topGrains, realSamples, messages, mode);
        String judgeResult = chatClient.prompt().user(judgePrompt).call().content();

        return parseJudgeResult(judgeResult, skillId, mode);
    }

    private String buildJudgePrompt(String aiReply, List<ExperienceGrain> grains,
            String realSamples, List<Map<String, String>> conversation, String mode) {
        return promptLoader.format("skill_evaluator.md", Map.of(
            "real_samples", realSamples,
            "grains_text", grainsToText(grains),
            "scripts", grainsToScripts(grains),
            "conversation", conversationToText(conversation)
        ), "sales.b2b_enterprise");
    }

    // ---- helpers ----

    private String extractAiResponses(List<Map<String, String>> messages) {
        return messages.stream()
            .filter(m -> "assistant".equals(m.get("role")))
            .map(m -> m.get("content"))
            .collect(Collectors.joining("\n---\n"));
    }

    private String extractLastUserQuestion(List<Map<String, String>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).get("role"))) {
                return messages.get(i).get("content");
            }
        }
        return "";
    }

    private UUID getSpaceId(UUID skillId) {
        return skillRepository.findById(skillId)
            .map(Skill::getSpaceId)
            .orElseThrow(() -> new RuntimeException("Skill not found: " + skillId));
    }

    private String loadRealConversationSamples(UUID skillId) {
        // 从该分身空间的颗粒中提取标准话术作为"真人样本"参考
        UUID spaceId = getSpaceId(skillId);
        return grainRepository.findBySpaceId(spaceId).stream()
            .filter(g -> g.getStandardScript() != null && !g.getStandardScript().isBlank())
            .limit(10)
            .map(ExperienceGrain::getStandardScript)
            .collect(Collectors.joining("\n---\n"));
    }

    private String grainsToText(List<ExperienceGrain> grains) {
        return grains.stream()
            .map(g -> String.format("[%s] 思考:%s 话术:%s",
                g.getSceneTag(),
                g.getExpertThought() != null ? g.getExpertThought() : "",
                g.getStandardScript() != null ? g.getStandardScript() : ""))
            .collect(Collectors.joining("\n"));
    }

    private String grainsToScripts(List<ExperienceGrain> grains) {
        return grains.stream()
            .filter(g -> g.getStandardScript() != null)
            .map(ExperienceGrain::getStandardScript)
            .collect(Collectors.joining("\n---\n"));
    }

    private String conversationToText(List<Map<String, String>> conversation) {
        return conversation.stream()
            .map(m -> String.format("[%s]: %s", m.get("role"), m.get("content")))
            .collect(Collectors.joining("\n"));
    }

    private SkillEvaluation parseJudgeResult(String json, UUID skillId, String mode) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return SkillEvaluation.builder()
                .id(UUID.randomUUID())
                .skillId(skillId)
                .mode(mode)
                .score(root.get("total_score").asInt())
                .styleScore(root.get("style_score").asInt())
                .consistencyScore(root.get("consistency_score").asInt())
                .behaviorScore(root.get("behavior_score").asInt())
                .scriptReuseScore(root.get("script_reuse_score").asInt())
                .scoreDetail(json)
                .strengths(root.get("strengths").toString())
                .improvements(root.get("improvements").toString())
                .demoScript(root.get("demo_script").asText())
                .createdAt(LocalDateTime.now())
                .build();
        } catch (Exception e) {
            log.error("AI Judge 评分解析失败, raw: {}", json, e);
            throw new RuntimeException("评分解析失败，请重试", e);
        }
    }
}
