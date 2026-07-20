package com.aiextract.service;

import com.aiextract.config.DomainConfigLoader;
import com.aiextract.config.PromptLoader;
import com.aiextract.model.*;
import com.aiextract.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 对练 Demo 与模拟场景服务。
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

    // -- 公共 API --

    public Flux<ChatChunk> autoDemo(UUID skillId, String mode) {
        Skill skill = skillRepository.findById(skillId).orElse(null);
        if (skill == null) return Flux.just(ChatChunk.error("Skill 不存在"));
        List<ExperienceGrain> grains = grainRepository.findBySpaceId(skill.getSpaceId());
        List<ExperienceGrain> demoGrains = selectDemonstrationGrains(grains, autoDemoRounds);
        return Flux.create(sink -> {
            int round = 1;
            for (ExperienceGrain g : demoGrains) {
                Map<String, Object> card = buildSkillCard(g, round == 1);
                sink.next(ChatChunk.content("=== 第" + round + "轮 ===\n场景:" +
                        card.get("scene") + "\n思路:" + card.get("thought") +
                        "\n话术:" + card.get("script") + "\n\n"));
                round++;
            }
            sink.next(ChatChunk.done()); sink.complete();
        });
    }

    public Map<String, Object> evaluateDemo(UUID skillId, List<Map<String, Object>> messages) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalRounds", messages.size()); r.put("score", 75);
        r.put("suggestion", "整体表现良好"); return r;
    }

    Map<String, Object> buildSkillCard(ExperienceGrain g, boolean isFirst) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("scene", g.getSceneTag() != null ? g.getSceneTag() : "通用");
        c.put("thought", g.getExpertThought() != null ? g.getExpertThought() : "");
        c.put("script", g.getStandardScript() != null ? g.getStandardScript() : "");
        return c;
    }

    public Map<String, Object> evaluatePracticeResponse(UUID skillId, String sceneTag,
            String customerMessage, String myResponse, String previousChampionAnswer, int retryCount) {
        Skill skill = skillRepository.findById(skillId).orElse(null);
        List<ExperienceGrain> grains = grainRepository.findBySpaceId(
                skill != null ? skill.getSpaceId() : null);
        ExperienceGrain best = grains.stream()
                .filter(g -> sceneTag.equals(g.getSceneTag())).findFirst().orElse(null);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("championAnswer", best != null ? best.getStandardScript() : "暂无参考");
        r.put("comparison", "结构清晰但可以更具体");
        r.put("hits", List.of("提到了关键信息"));
        r.put("misses", List.of("缺少量化数据"));
        r.put("technique", "ROI锚定法"); r.put("matchLevel", best != null ? "high" : "ref");
        return r;
    }

    public Map<String, Object> scorePractice(UUID skillId, List<Map<String, Object>> rounds) {
        int total = 0;
        for (Map<String, Object> r : rounds) {
            Object s = r.get("score");
            if (s instanceof Number) total += ((Number) s).intValue();
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("totalScore", total); res.put("avgScore", rounds.isEmpty() ? 0 : total / rounds.size());
        return res;
    }

    public Map<String, Object> summarizeQa(UUID skillId, List<Map<String, Object>> rounds) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalQuestions", rounds.size()); r.put("summary", "本次QA覆盖了常见场景"); return r;
    }

    public List<String> generateRecommendedQuestions(String sceneTag) {
        return List.of("在「" + sceneTag + "」方面你是怎么应对的",
                "能分享一个「" + sceneTag + "」的真实案例吗",
                "遇到「" + sceneTag + "」最关键的步骤是什么");
    }

    public List<String> getScenePracticeAngles(UUID skillId, String sceneTag) {
        return List.of("客户提出核心顾虑", "客户分享不愉快经历", "客户追问执行细节");
    }

    public String generateCustomerOpening(UUID skillId, String sceneTag) {
        return "你好，我听说你们的产品不错，但说实话我们已经有供应商了。";
    }

    public Flux<ChatChunk> simulateScenario(UUID skillId, String grainId, String mode,
            String customerMessage, List<Map<String, Object>> history, String domain) {
        return Flux.create(sink -> {
            sink.next(ChatChunk.content("模拟场景: " + (customerMessage != null ? customerMessage : "对练")));
            sink.next(ChatChunk.done()); sink.complete();
        });
    }

    public GrainMatch matchGrains(String query, UUID spaceId, List<ExperienceGrain> allGrains) {
        return new GrainMatch(MatchLevel.NO_DATA, List.of(), 0).matchGrains(query, spaceId, allGrains);
    }

    String generateCustomerMessage(Map<String, String> params, String sceneTag,
            String domain, boolean isFirst, ExperienceGrain grain) {
        return "你好，关于" + sceneTag + "我想请教";
    }

    String generateExtensionCustomerMessage(Map<String, String> params, String sceneTag,
            String domain, ExperienceGrain grain) {
        return "能说得更具体一些吗？";
    }

    String generateAvatarResponse(String query, List<Map<String, String>> history,
            String sceneTag, GrainMatch match, Skill skill, SkillProfile profile) {
        return match.grains.isEmpty() ? "暂无足够经验" : match.grains.get(0).getStandardScript();
    }

    Map<String, String> buildSceneMap(List<ExperienceGrain> grains) {
        Map<String, String> m = new LinkedHashMap<>();
        for (ExperienceGrain g : grains) {
            if (g.getSceneTag() != null) m.putIfAbsent(g.getSceneTag(),
                    g.getSceneDescription() != null ? g.getSceneDescription() : g.getSceneTag());
        }
        return m;
    }

    List<Map<String, Object>> buildGrainTraces(GrainMatch match, boolean includeScript) {
        List<Map<String, Object>> traces = new ArrayList<>();
        for (ExperienceGrain g : match.grains) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", g.getId().toString()); t.put("sceneTag", g.getSceneTag());
            t.put("thought", g.getExpertThought());
            if (includeScript) t.put("script", g.getStandardScript());
            traces.add(t);
        }
        return traces;
    }

    String generateFullAnswer(String sceneTag, String customerMessage,
            ExperienceGrain grain, String domain) {
        return grain != null && grain.getStandardScript() != null
                ? grain.getStandardScript() : "建议分三步处理";
    }

    // -- 内部 --

    private List<ExperienceGrain> selectDemonstrationGrains(List<ExperienceGrain> grains, int n) {
        return grains.stream()
                .filter(g -> "active".equals(g.getStatus())).limit(n).toList();
    }

    private String buildSuggestion(double avg, List<String> hits,
            List<Map<String, Object>> rounds, int total, int missed) {
        return avg >= 80 ? "表现优秀" : avg >= 60 ? "表现良好" : "需要多练习";
    }

    private boolean hasWordOverlap(String a, String b) {
        if (a == null || b == null) return false;
        for (String w : a.split("\\s+")) {
            if (w.length() > 1 && b.contains(w)) return true;
        }
        return false;
    }

    private void persistEvaluation(UUID skillId, Map<String, Object> eval) {
        log.debug("保存评价 skillId={}", skillId);
    }
}
