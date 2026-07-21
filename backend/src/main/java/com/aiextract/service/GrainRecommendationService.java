package com.aiextract.service;

import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Skill;
import com.aiextract.repository.ExperienceGrainRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 颗粒推荐服务 — 关键词相关性打分 + 推荐问题生成。
 *
 * <p>从 {@link SkillService} 提取，聚焦于颗粒检索后的推荐与排序逻辑。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrainRecommendationService {

    private final ExperienceGrainRepository grainRepository;

    /**
     * 关键词相关性打分 — 多维度加权计分。
     */
    public int relevanceScore(ExperienceGrain grain, String query) {
        int score = 0;
        String q = query.toLowerCase();
        if (grain.getSceneTag() != null && grain.getSceneTag().toLowerCase().contains(q)) score += 5;
        if (grain.getSceneDescription() != null && grain.getSceneDescription().toLowerCase().contains(q)) score += 3;
        if (grain.getExpertThought() != null && grain.getExpertThought().toLowerCase().contains(q)) score += 4;
        if (grain.getStandardScript() != null && grain.getStandardScript().toLowerCase().contains(q)) score += 2;
        for (String word : q.split("")) {
            if (word.length() <= 1) continue;
            if (grain.getSceneDescription() != null && grain.getSceneDescription().contains(word)) score += 1;
            if (grain.getExpertThought() != null && grain.getExpertThought().contains(word)) score += 1;
        }
        return score;
    }

    /**
     * 生成推荐问题 — 从覆盖度最高的场景中取颗粒描述转为问句。
     *
     * @param skill 分身对象
     * @return 最多 3 个推荐问题（不足时用通用问题补全）
     */
    public List<String> generateSuggestedQuestions(Skill skill) {
        java.util.Map<String, Long> sceneCounts = grainRepository.findBySpaceId(skill.getSpaceId()).stream()
            .filter(g -> g.getSceneTag() != null && !g.getSceneTag().isEmpty())
            .collect(Collectors.groupingBy(
                ExperienceGrain::getSceneTag,
                Collectors.counting()));
        List<String> topScenes = sceneCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(3)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        List<String> questions = new ArrayList<>();
        for (String scene : topScenes) {
            var grains = grainRepository.findBySpaceIdAndSceneTagAndStatus(
                skill.getSpaceId(), scene, "active");
            if (!grains.isEmpty()) {
                String desc = grains.get(0).getSceneDescription();
                if (desc != null && desc.length() > 5) {
                    questions.add(desc.endsWith("？") || desc.endsWith("?") ? desc : "如何" + desc + "？");
                }
            }
        }
        if (questions.size() < 3) {
            if (questions.stream().noneMatch(q -> q.contains("最成功的案例"))) {
                questions.add("能分享一个你最成功的案例吗？");
            }
            if (questions.stream().noneMatch(q -> q.contains("拒绝"))) {
                questions.add("遇到客户拒绝时，你会怎么处理？");
            }
        }
        return questions.stream().limit(3).collect(Collectors.toList());
    }
}
