package com.aiextract.service;

import com.aiextract.config.DomainConfigLoader;
import com.aiextract.config.PromptLoader;
import com.aiextract.model.*;
import com.aiextract.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 对练 Demo 与模拟场景服务。
 *
 * <p>核心能力：AI 模拟客户自动对练、逐轮评估、多轮综合评分、QA 覆盖率分析。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PracticeDemoService {

    private final SkillRepository skillRepository;
    private final SkillProfileRepository profileRepository;
    private final SkillMaterialRepository materialRepository;
    private final SkillEvaluationRepository evaluationRepository;
    private final ExperienceGrainRepository grainRepository;
    private final ChatStreamAdapter chatStreamAdapter;
    private final GrainRetriever grainRetriever;
    private final PromptLoader promptLoader;
    private final DomainConfigLoader domainConfigLoader;
    private final ObjectMapper objectMapper;

    @Value("${app.evaluation.auto-demo-rounds:10}")
    private int autoDemoRounds;
    @Value("${app.practice.max-retries-per-angle:2}")
    private int maxRetriesPerAngle;

    // ==================== 内部类 ====================

    /** 颗粒匹配结果 */
    public static class GrainMatch {
        public MatchLevel level;
        public List<ExperienceGrain> grains;
        public double maxCosine;

        public GrainMatch(MatchLevel level, List<ExperienceGrain> grains, double maxCosine) {
            this.level = level; this.grains = grains; this.maxCosine = maxCosine;
        }

        public GrainMatch matchGrains(String query, UUID spaceId, List<ExperienceGrain> allGrains) {
            List<ExperienceGrain> hits = new ArrayList<>();
            for (ExperienceGrain g : allGrains) {
                if (hasGrainKeywordHit(g, query)) hits.add(g);
            }
            hits.sort((a, b) -> Integer.compare(grainKeywordScore(b, query), grainKeywordScore(a, query)));
            return new GrainMatch(hits.isEmpty() ? MatchLevel.NO_DATA : MatchLevel.SEMANTIC,
                    hits.stream().limit(5).toList(), 0);
        }

        boolean hasGrainKeywordHit(ExperienceGrain g, String query) {
            return grainKeywordScore(g, query) > 0;
        }

        int grainKeywordScore(ExperienceGrain g, String query) {
            int score = 0;
            String q = query.toLowerCase();
            if (g.getSceneTag() != null && g.getSceneTag().toLowerCase().contains(q)) score += 5;
            if (g.getSceneDescription() != null && g.getSceneDescription().toLowerCase().contains(q)) score += 3;
            if (g.getExpertThought() != null && g.getExpertThought().toLowerCase().contains(q)) score += 4;
            if (g.getStandardScript() != null && g.getStandardScript().toLowerCase().contains(q)) score += 2;
            return score;
        }

        int simpleRelevance(ExperienceGrain grain, String query) {
            int score = 0;
            String q = query.toLowerCase();
            if (grain.getSceneTag() != null && grain.getSceneTag().toLowerCase().contains(q)) score += 5;
            if (grain.getSceneDescription() != null && grain.getSceneDescription().toLowerCase().contains(q)) score += 3;
            if (grain.getExpertThought() != null && grain.getExpertThought().toLowerCase().contains(q)) score += 4;
            if (grain.getStandardScript() != null && grain.getStandardScript().toLowerCase().contains(q)) score += 2;
            return score;
        }
    }

    /** 匹配精度 */
    public enum MatchLevel { EXACT, SEMANTIC, PROFILE_GUESS, NO_DATA, NON_BUSINESS }

    // ==================== 公开 API ====================

    /**
     * AI 生成客户开场白。根据分身画像和场景标签，生成一句自然的客户开场。
     */
    public String generateCustomerOpening(UUID skillId, String sceneTag) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Skill 不存在: " + skillId));
        SkillProfile profile = profileRepository.findBySkillId(skillId).orElse(null);
        String domain = domainConfigLoader.resolveDomain(skill);
        String persona = buildPersonaContext(skill, profile);

        String prompt = promptLoader.format("practice_customer_opening.md",
                Map.of("persona", persona, "sceneTag", sceneTag != null ? sceneTag : ""), domain);
        long t0 = System.currentTimeMillis();
        String raw = chatStreamAdapter.chat(prompt);
        log.info("生成客户开场白 skillId={} sceneTag={} time={}ms len={}",
                skillId, sceneTag, System.currentTimeMillis() - t0, raw != null ? raw.length() : 0);
        return nn(raw);
    }

    /**
     * 获取场景练习角度。从活跃颗粒的 sceneDescription 去重取前 10 条。
     */
    public List<String> getScenePracticeAngles(UUID skillId, String sceneTag) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Skill 不存在: " + skillId));
        List<ExperienceGrain> grains = grainRepository.findBySpaceId(skill.getSpaceId());
        List<String> angles = grains.stream()
                .filter(g -> "active".equals(g.getStatus()))
                .filter(g -> sceneTag == null || sceneTag.isBlank() || sceneTag.equals(g.getSceneTag()))
                .map(ExperienceGrain::getSceneDescription)
                .filter(Objects::nonNull)
                .filter(d -> !d.isBlank())
                .distinct()
                .limit(10)
                .collect(Collectors.toList());
        log.info("获取练习角度 skillId={} sceneTag={} count={}", skillId, sceneTag, angles.size());
        return angles;
    }

    /**
     * 对练单轮评估。对比学员回答与销冠标准答案，给出即时反馈。
     */
    public Map<String, Object> evaluatePracticeResponse(UUID skillId, String sceneTag,
            String customerMessage, String myResponse, String previousChampionAnswer, int retryCount) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Skill 不存在: " + skillId));
        String domain = domainConfigLoader.resolveDomain(skill);

        // 查找最佳匹配颗粒
        List<ExperienceGrain> grains = grainRepository.findBySpaceId(skill.getSpaceId()).stream()
                .filter(g -> "active".equals(g.getStatus())).collect(Collectors.toList());
        ExperienceGrain bestGrain = grains.stream()
                .filter(g -> sceneTag != null && sceneTag.equals(g.getSceneTag()))
                .max(Comparator.comparing(g -> g.getQualityScore() != null ? g.getQualityScore() : 0))
                .orElse(null);
        String championAnswer = bestGrain != null && bestGrain.getStandardScript() != null
                ? bestGrain.getStandardScript() : "暂无参考话术";

        String prompt = promptLoader.format("practice_evaluate_round.md", Map.of(
                "sceneTag", sceneTag != null ? sceneTag : "",
                "customerMessage", customerMessage != null ? customerMessage : "",
                "myResponse", myResponse != null ? myResponse : "",
                "championAnswer", championAnswer,
                "retryCount", String.valueOf(retryCount),
                "maxRetries", String.valueOf(maxRetriesPerAngle)), domain);

        long t0 = System.currentTimeMillis();
        String raw = chatStreamAdapter.chat(prompt);
        log.info("对练单轮评估 skillId={} sceneTag={} retryCount={} time={}ms",
                skillId, sceneTag, retryCount, System.currentTimeMillis() - t0);

        Map<String, Object> parsed = parseAiJson(raw);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("championAnswer", parsed.getOrDefault("championAnswer", championAnswer));
        r.put("comparison", parsed.getOrDefault("comparison", ""));
        r.put("hits", parsed.getOrDefault("hits", List.of()));
        r.put("misses", parsed.getOrDefault("misses", List.of()));
        r.put("technique", parsed.getOrDefault("technique", ""));
        r.put("offTopic", parsed.getOrDefault("offTopic", false));
        r.put("fullAnswer", parsed.getOrDefault("fullAnswer", championAnswer));
        r.put("isLastRetry", retryCount >= maxRetriesPerAngle);
        r.put("matchLevel", parsed.getOrDefault("matchLevel", bestGrain != null ? "SEMANTIC" : "NO_DATA"));
        r.put("grains", bestGrain != null
                ? buildGrainTraces(new GrainMatch(MatchLevel.SEMANTIC, List.of(bestGrain), 0), false)
                : List.of());
        return r;
    }

    /**
     * Demo 演示质量评估。从五维度评估自动生成对话的质量。
     */
    public Map<String, Object> evaluateDemo(UUID skillId, List<Map<String, Object>> messages) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Skill 不存在: " + skillId));
        String domain = domainConfigLoader.resolveDomain(skill);
        String history = formatHistory(messages);

        String prompt = promptLoader.format("practice_evaluate.md",
                Map.of("history", history), domain);
        long t0 = System.currentTimeMillis();
        String raw = chatStreamAdapter.chat(prompt);
        log.info("Demo评估 skillId={} messages={} time={}ms",
                skillId, messages.size(), System.currentTimeMillis() - t0);
        return parseAiJson(raw);
    }

    /**
     * 多轮对练综合评分。分析技法掌握度、风险轮次、溯源覆盖。
     */
    public Map<String, Object> scorePractice(UUID skillId, List<Map<String, Object>> rounds) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Skill 不存在: " + skillId));
        String domain = domainConfigLoader.resolveDomain(skill);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rounds.size(); i++) {
            Map<String, Object> r = rounds.get(i);
            sb.append(String.format("第%d轮 场景:%s 技法:%s 命中:%s 遗漏:%s\n",
                    i + 1,
                    r.getOrDefault("sceneTag", ""),
                    r.getOrDefault("technique", ""),
                    r.getOrDefault("hits", ""),
                    r.getOrDefault("misses", "")));
        }
        String roundsText = sb.toString();

        String prompt = promptLoader.format("practice_score.md",
                Map.of("rounds", roundsText, "totalRounds", String.valueOf(rounds.size())), domain);
        long t0 = System.currentTimeMillis();
        String raw = chatStreamAdapter.chat(prompt);
        log.info("对练评分 skillId={} rounds={} time={}ms",
                skillId, rounds.size(), System.currentTimeMillis() - t0);
        return parseAiJson(raw);
    }

    /**
     * QA 知识点覆盖总结。分析对话记录中已覆盖和缺失的场景。
     */
    public Map<String, Object> summarizeQa(UUID skillId, List<Map<String, Object>> rounds) {
        Skill skill = skillRepository.findById(skillId)
                .orElseThrow(() -> new RuntimeException("Skill 不存在: " + skillId));
        String domain = domainConfigLoader.resolveDomain(skill);
        String history = formatHistory(rounds);

        String prompt = promptLoader.format("practice_qa_summary.md",
                Map.of("history", history), domain);
        long t0 = System.currentTimeMillis();
        String raw = chatStreamAdapter.chat(prompt);
        log.info("QA总结 skillId={} rounds={} time={}ms",
                skillId, rounds.size(), System.currentTimeMillis() - t0);
        return parseAiJson(raw);
    }

    /**
     * 生成推荐问题（模板版）。根据单个场景标签生成 3 条固定句式问题。
     */
    public List<String> generateRecommendedQuestions(String sceneTag) {
        return List.of(
                "在「" + sceneTag + "」方面你是怎么应对的",
                "能分享一个「" + sceneTag + "」的真实案例吗",
                "遇到「" + sceneTag + "」最关键的步骤是什么");
    }

    /**
     * 基于分身活跃颗粒的模板推荐问题（cache-miss 兜底）。
     *
     * <p>遍历 skill 的所有活跃场景标签，每个生成 3 条模板问题并合并去重。
     * 优先使用 {@link SkillService#generateRecommendedQuestions(UUID)} 预生成的缓存。</p>
     */
    public List<String> generateRecommendedQuestionsForSkill(UUID skillId) {
        Skill skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null) return List.of();
        Set<String> all = new LinkedHashSet<>();
        grainRepository.findBySpaceId(skill.getSpaceId()).stream()
            .filter(g -> g.getSceneTag() != null && "active".equals(g.getStatus()))
            .map(g -> g.getSceneTag()).distinct()
            .forEach(tag -> all.addAll(generateRecommendedQuestions(tag)));
        return new ArrayList<>(all);
    }

    /**
     * 全自动演示（SSE 流）。AI 逐轮生成客户开场 + 销冠完整回答。
     *
     * <p>SSE 事件：customer（客户说的）+ avatar（销冠回答 + 溯源）+ done</p>
     */
    public Flux<ChatChunk> autoDemo(UUID skillId, String mode) {
        Skill skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null) return Flux.just(ChatChunk.error("Skill 不存在"));

        List<ExperienceGrain> grains = grainRepository.findBySpaceId(skill.getSpaceId()).stream()
                .filter(g -> "active".equals(g.getStatus())).collect(Collectors.toList());
        if (grains.isEmpty())
            return Flux.just(ChatChunk.content("该分身暂无活跃颗粒，无法演示"), ChatChunk.done());

        int rounds = "quick".equals(mode) ? Math.min(3, autoDemoRounds) : autoDemoRounds;
        List<ExperienceGrain> selected = selectDemonstrationGrains(grains, rounds);
        String domain = domainConfigLoader.resolveDomain(skill);

        log.info("开始自动演示 skillId={} mode={} rounds={}", skillId, mode, selected.size());

        return Flux.fromIterable(selected).concatMap(grain -> {
            String opening = generateCustomerOpening(skillId, grain.getSceneTag());
            String fullAnswer = generateFullAnswer(
                    grain.getSceneDescription() != null ? grain.getSceneDescription() : "",
                    opening, grain, domain);
            List<Map<String, Object>> traces = buildGrainTraces(
                    new GrainMatch(MatchLevel.EXACT, List.of(grain), 0), false);
            return Flux.just(
                    ChatChunk.event("customer",
                            Map.of("content", opening, "sceneTag",
                                    grain.getSceneTag() != null ? grain.getSceneTag() : "")),
                    ChatChunk.event("avatar",
                            Map.of("content", fullAnswer, "grains", traces, "matchLevel", "EXACT")));
        }).concatWithValues(ChatChunk.done())
                .doOnComplete(() -> log.info("自动演示完成 skillId={} rounds={}", skillId, selected.size()))
                .doOnError(e -> log.error("自动演示异常 skillId={}", skillId, e));
    }

    /**
     * 模拟对练场景（SSE 流）。管理员在审核页手动触发单轮对练。
     */
    public Flux<ChatChunk> simulateScenario(UUID skillId, String grainId, String mode,
            String customerMessage, List<Map<String, Object>> history, String domain) {
        Skill skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null) return Flux.just(ChatChunk.error("Skill 不存在"));

        SkillProfile profile = profileRepository.findBySkillId(skillId).orElse(null);
        List<ExperienceGrain> grains = grainRepository.findBySpaceId(skill.getSpaceId()).stream()
                .filter(g -> "active".equals(g.getStatus())).collect(Collectors.toList());

        ExperienceGrain selectedGrain;
        if (grainId != null && !grainId.isBlank()) {
            UUID gid = UUID.fromString(grainId);
            selectedGrain = grains.stream().filter(g -> g.getId().equals(gid)).findFirst().orElse(null);
        } else {
            List<ExperienceGrain> autoPick = selectDemonstrationGrains(grains, 1);
            selectedGrain = autoPick.isEmpty() ? null : autoPick.get(0);
        }
        if (selectedGrain == null)
            return Flux.just(ChatChunk.content("无可用颗粒，请先上传素材并完成萃取"), ChatChunk.done());

        String personaCtx = buildPersonaContext(skill, profile);
        String grainCtx = buildGrainContext(List.of(selectedGrain));
        String historyStr = formatHistory(history);
        String resolvedDomain = domain != null ? domain : domainConfigLoader.resolveDomain(skill);

        String prompt = promptLoader.format("practice_simulate.md", Map.of(
                "persona", personaCtx,
                "grain", grainCtx,
                "history", historyStr,
                "message", customerMessage != null ? customerMessage : "",
                "mode", mode != null ? mode : "with_skill"), resolvedDomain);

        log.info("开始模拟对练 skillId={} grainId={} mode={}", skillId, grainId, mode);

        List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", prompt));
        return chatStreamAdapter.chatStream(messages, Map.of("skillId", skillId.toString()))
                .map(eventMap -> {
                    String type = eventMap.get("type") != null
                            ? eventMap.get("type").toString() : "content";
                    if ("done".equals(type)) return ChatChunk.done();
                    String content = eventMap.get("content") != null
                            ? eventMap.get("content").toString() : "";
                    return ChatChunk.content(content);
                })
                .doOnComplete(() -> log.info("模拟对练完成 skillId={}", skillId))
                .doOnError(e -> log.error("模拟对练异常 skillId={}", skillId, e));
    }

    /**
     * 颗粒匹配。根据查询文本匹配最相关的经验颗粒。
     */
    public GrainMatch matchGrains(String query, UUID spaceId, List<ExperienceGrain> allGrains) {
        return new GrainMatch(MatchLevel.NO_DATA, List.of(), 0).matchGrains(query, spaceId, allGrains);
    }

    // ==================== 内部辅助方法（Prompt 构建） ====================

    /** 构建销冠画像上下文文本 */
    String buildPersonaContext(Skill skill, SkillProfile profile) {
        StringBuilder sb = new StringBuilder();
        sb.append("姓名: ").append(skill.getOwnerName() != null ? skill.getOwnerName() : "未知").append("\n");
        sb.append("职位: ").append(skill.getOwnerTitle() != null ? skill.getOwnerTitle() : "").append("\n");
        sb.append("部门: ").append(skill.getDepartment() != null ? skill.getDepartment() : "").append("\n");
        sb.append("资历: ").append(skill.getSeniority() != null ? skill.getSeniority() : "").append("\n");
        sb.append("标签: ").append(skill.getTags() != null ? skill.getTags() : "").append("\n");
        if (profile != null) {
            if (profile.getPersonality() != null) sb.append("性格: ").append(profile.getPersonality()).append("\n");
            if (profile.getSpeakingStyle() != null)
                sb.append("说话风格: ").append(profile.getSpeakingStyle()).append("\n");
            if (profile.getBackground() != null) sb.append("背景: ").append(profile.getBackground()).append("\n");
            if (profile.getCommonPhrases() != null)
                sb.append("口头禅: ").append(profile.getCommonPhrases()).append("\n");
        }
        return sb.toString();
    }

    /** 构建颗粒上下文文本 */
    String buildGrainContext(List<ExperienceGrain> grains) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < grains.size(); i++) {
            ExperienceGrain g = grains.get(i);
            sb.append("--- 颗粒 ").append(i + 1).append(" ---\n");
            sb.append("场景: ").append(g.getSceneTag() != null ? g.getSceneTag() : "").append("\n");
            sb.append("描述: ").append(g.getSceneDescription() != null ? g.getSceneDescription() : "").append("\n");
            sb.append("思路: ").append(g.getExpertThought() != null ? g.getExpertThought() : "").append("\n");
            sb.append("话术: ").append(g.getStandardScript() != null ? g.getStandardScript() : "").append("\n");
            sb.append("常见错误: ").append(g.getCommonMistakes() != null ? g.getCommonMistakes() : "").append("\n");
        }
        return sb.toString();
    }

    /** 格式化对话历史 */
    String formatHistory(List<Map<String, Object>> history) {
        if (history == null || history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : history) {
            String role = msg.get("role") != null ? msg.get("role").toString() : "unknown";
            String content = msg.get("content") != null ? msg.get("content").toString() : "";
            sb.append(role).append(": ").append(content).append("\n");
        }
        return sb.toString();
    }

    // ==================== 内部辅助方法（工具） ====================

    /** 解析 AI 返回的 JSON */
    @SuppressWarnings("unchecked")
    Map<String, Object> parseAiJson(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("AI 返回空响应");
            return Map.of("error", "AI 返回空响应");
        }
        try {
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return objectMapper.readValue(raw.substring(start, end + 1), Map.class);
            }
            return Map.of("raw", raw);
        } catch (JsonProcessingException e) {
            log.warn("AI JSON 解析失败: {}", e.getMessage());
            return Map.of("raw", raw, "parseError", e.getMessage());
        }
    }

    /** 去掉字符串首尾空白和引号 */
    private String nn(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("^[\"'\\s]+|[\"'\\s]+$", "");
    }

    /** 生成完整销冠答案 */
    String generateFullAnswer(String sceneDesc, String customerMessage,
            ExperienceGrain grain, String domain) {
        if (grain == null) return "建议分三步处理：倾听客户真实需求、用数据说话、给出可执行的承诺。";
        String prompt = promptLoader.format("practice_simulate.md", Map.of(
                "persona", "",
                "grain", buildGrainContext(List.of(grain)),
                "history", "",
                "message", customerMessage != null ? customerMessage : "",
                "mode", "with_skill"), domain);
        String raw = chatStreamAdapter.chat(prompt);
        return nn(raw);
    }

    /** 选取 N 个最具代表性的演示颗粒（按场景去重 + qualityScore 排序） */
    private List<ExperienceGrain> selectDemonstrationGrains(List<ExperienceGrain> grains, int n) {
        List<ExperienceGrain> active = grains.stream()
                .filter(g -> "active".equals(g.getStatus()))
                .sorted((a, b) -> Double.compare(
                        b.getQualityScore() != null ? b.getQualityScore() : 0,
                        a.getQualityScore() != null ? a.getQualityScore() : 0))
                .collect(Collectors.toList());
        // 按场景去重
        Map<String, ExperienceGrain> bestPerScene = new LinkedHashMap<>();
        for (ExperienceGrain g : active) {
            String tag = g.getSceneTag() != null ? g.getSceneTag() : "通用";
            bestPerScene.putIfAbsent(tag, g);
        }
        List<ExperienceGrain> result = new ArrayList<>(bestPerScene.values());
        // 不够 N 个时从剩余补充
        if (result.size() < n) {
            for (ExperienceGrain g : active) {
                if (!result.contains(g)) result.add(g);
                if (result.size() >= n) break;
            }
        }
        return result.size() > n ? result.subList(0, n) : result;
    }

    /** 构建颗粒溯源列表 */
    List<Map<String, Object>> buildGrainTraces(GrainMatch match, boolean includeScript) {
        List<Map<String, Object>> traces = new ArrayList<>();
        for (ExperienceGrain g : match.grains) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", g.getId().toString());
            t.put("sceneTag", g.getSceneTag());
            t.put("thought", g.getExpertThought());
            if (includeScript) t.put("script", g.getStandardScript());
            traces.add(t);
        }
        return traces;
    }

    Map<String, Object> buildSkillCard(ExperienceGrain g, boolean isFirst) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("scene", g.getSceneTag() != null ? g.getSceneTag() : "通用");
        c.put("thought", g.getExpertThought() != null ? g.getExpertThought() : "");
        c.put("script", g.getStandardScript() != null ? g.getStandardScript() : "");
        return c;
    }

    Map<String, String> buildSceneMap(List<ExperienceGrain> grains) {
        Map<String, String> m = new LinkedHashMap<>();
        for (ExperienceGrain g : grains) {
            if (g.getSceneTag() != null)
                m.putIfAbsent(g.getSceneTag(),
                        g.getSceneDescription() != null ? g.getSceneDescription() : g.getSceneTag());
        }
        return m;
    }

    String generateCustomerMessage(Map<String, String> params, String sceneTag,
            String domain, boolean isFirst, ExperienceGrain grain) {
        if (isFirst) return generateCustomerOpening(
                grainRepository.findBySpaceId(
                        skillRepository.findById(grain.getSpaceId()).orElseThrow().getSpaceId())
                        .stream().findFirst().map(g -> g.getSpaceId()).orElse(UUID.randomUUID()),
                sceneTag);
        return "关于" + (sceneTag != null ? sceneTag : "这个场景") + "，还有没有更具体的例子？";
    }

    String generateExtensionCustomerMessage(Map<String, String> params, String sceneTag,
            String domain, ExperienceGrain grain) {
        return "能说得更具体一些吗？";
    }

    String generateAvatarResponse(String query, List<Map<String, String>> history,
            String sceneTag, GrainMatch match, Skill skill, SkillProfile profile) {
        if (match == null || match.grains.isEmpty()) return "这个问题我暂时没有足够的经验来回答，让我换个角度帮你分析。";
        String domain = domainConfigLoader.resolveDomain(skill);
        String grainCtx = buildGrainContext(match.grains);
        String prompt = promptLoader.format("practice_simulate.md", Map.of(
                "persona", buildPersonaContext(skill, profile),
                "grain", grainCtx,
                "history", "",
                "message", query != null ? query : "",
                "mode", "with_skill"), domain);
        return nn(chatStreamAdapter.chat(prompt));
    }

    private boolean hasWordOverlap(String a, String b) {
        if (a == null || b == null) return false;
        for (String w : a.split("\\s+")) {
            if (w.length() > 1 && b.contains(w)) return true;
        }
        return false;
    }

    @SuppressWarnings("unused")
    private String buildSuggestion(double avg, List<String> hits,
            List<Map<String, Object>> rounds, int total, int missed) {
        return avg >= 80 ? "表现优秀" : avg >= 60 ? "表现良好" : "需要多练习";
    }

    @SuppressWarnings("unused")
    private void persistEvaluation(UUID skillId, Map<String, Object> eval) {
        log.debug("保存评价 skillId={}", skillId);
    }
}
