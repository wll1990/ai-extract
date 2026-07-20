package com.aiextract.service.precheck;

import com.aiextract.config.DomainConfig;
import com.aiextract.config.DomainConfigLoader;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.repository.ExperienceGrainRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 素材预检引擎 — 编排准入检查（Gate 1）和质量预检（Gate 2）。
 *
 * <p>纯规则引擎，零 AI 调用，延迟 &lt;50ms。</p>
 *
 * <p>用法：
 * <pre>{@code
 * AcceptanceResult acceptance = preChecker.checkAcceptance(text, "sales");
 * if (!acceptance.passed()) { ... reject ... }
 * PreCheckResult quality = preChecker.evaluate(text, "sales", spaceId);
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaterialPreChecker {

    private final DomainConfigLoader domainConfigLoader;
    private final ExperienceGrainRepository grainRepository;

    private final StructureAnalyzer structureAnalyzer = new StructureAnalyzer();
    private final ContentAnalyzer contentAnalyzer = new ContentAnalyzer();
    private final QualityAnalyzer qualityAnalyzer = new QualityAnalyzer();

    // ============================================================
    // Gate 1: 准入检查
    // ============================================================

    /**
     * 检查素材是否符合准入标准。不通过应直接拒绝，不进入流水线。
     */
    public AcceptanceResult checkAcceptance(String text, String domain) {
        DomainConfig config = domainConfigLoader.load(domain);
        DomainConfig.AcceptanceConfig accConfig = config.getAcceptance();
        if (accConfig == null) {

            return AcceptanceResult.pass();

        }

        // 内容过短
        if (text == null || text.length() < accConfig.getMinTextLength()) {
            return AcceptanceResult.reject("TOO_SHORT",
                    "内容过短（仅 " + (text == null ? 0 : text.length()) + " 字），无法进行有效萃取。请上传 ≥"
                            + accConfig.getMinTextLength() + " 字的完整对话或经验分享",
                    Map.of("textLength", text == null ? 0 : text.length(), "minRequired", accConfig.getMinTextLength()));
        }

        // 销售领域检测 + 教科书检测 + 营销检测 + 中文检测
        AcceptanceResult contentResult = contentAnalyzer.checkAcceptance(text, accConfig);
        if (!contentResult.passed()) return contentResult;

        return AcceptanceResult.pass();
    }

    /**
     * 检查重复 — 比较新素材文本与已有素材原文及锦囊文本，双重校验。
     * 内部加载领域配置获取重复阈值。
     *
     * @param existingMaterialTexts 同 skill 下已有素材的解析文本（可为空列表）
     */
    public AcceptanceResult checkDuplicate(String text, UUID spaceId, String domain,
                                           List<String> existingMaterialTexts) {
        DomainConfig config = domainConfigLoader.load(domain);
        DomainConfig.AcceptanceConfig accConfig = config.getAcceptance();
        double threshold = accConfig != null ? accConfig.getDuplicateSimilarityMax() : 0.90;
        if (spaceId == null) {

            return AcceptanceResult.pass();

        }
        { if (text == null || text.isBlank()) return AcceptanceResult.pass(); }

        double maxSim = 0;

        // 1. 与已有素材原文比较（主要防线）
        if (existingMaterialTexts != null) for (String existingText : existingMaterialTexts) {
     {
                { if (existingText == null || existingText.isBlank()) continue;
 }
                double sim = textSimilarity(text, existingText);
                if (sim > maxSim) {

                    maxSim = sim;

                }
            }
        }

        // 2. 与已有锦囊比较（辅助防线，锦囊是摘要，相似度通常偏低）
        List<ExperienceGrain> existing = grainRepository.findBySpaceId(spaceId);
        for (ExperienceGrain g : existing) {
            String grainText = (g.getSceneDescription() != null ? g.getSceneDescription() : "")
                    + (g.getExpertThought() != null ? g.getExpertThought() : "");
            double sim = textSimilarity(text, grainText);
            if (sim > maxSim) {

                maxSim = sim;

            }
        }

        if (maxSim > threshold) {
            return AcceptanceResult.reject("DUPLICATE",
                    "该内容与已有素材高度重复（相似度 " + pct(maxSim) + "），请勿重复上传",
                    Map.of("similarity", maxSim, "threshold", threshold));
        }
        return AcceptanceResult.pass();
    }

    // ============================================================
    // Gate 2: 质量预检
    // ============================================================

    /**
     * 评估素材质量，返回评分 + 预估 + 逐项反馈。不阻断上传。
     */
    public PreCheckResult evaluate(String text, String domain, UUID spaceId) {
        DomainConfig config = domainConfigLoader.load(domain);
        DomainConfig.PreCheckConfig preConfig = config.getPrecheck();
        if (preConfig == null) {
            return new PreCheckResult(50, "warning", 0, 0, List.of(), List.of());
        }

        String roleLabel = config.getDomain() != null ? config.getDomain().getRoleLabel() : "销冠";
        String counterpartyLabel = config.getDomain() != null ? config.getDomain().getCounterpartyLabel() : "客户";

        List<CheckItem> allChecks = new ArrayList<>();
        allChecks.addAll(structureAnalyzer.analyze(text, preConfig, roleLabel, counterpartyLabel));
        allChecks.addAll(contentAnalyzer.analyze(text, preConfig));
        allChecks.addAll(qualityAnalyzer.analyze(text, preConfig));

        // 重复检测（需要 DB）
        if (spaceId != null) {
            allChecks.add(qualityAnalyzer.checkDuplicate(text, getExistingGrainTexts(spaceId),
                    preConfig.getDuplicateSimilarityWarn()));
        }

        // 综合评分：结构 40% + 内容 35% + 质量 25%
        double structureAvg = averageScore(allChecks, "structure");
        double contentAvg = averageScore(allChecks, "content");
        double qualityAvg = averageScore(allChecks, "quality");
        int overallScore = (int) (structureAvg * 0.40 + contentAvg * 0.35 + qualityAvg * 0.25);

        String grade = overallScore >= 70 ? "good" : overallScore >= 50 ? "warning" : "poor";

        // 预估颗粒数：对话轮数 × 业务密度系数 × 0.3
        int turns = countDialogueTurns(text, roleLabel, counterpartyLabel);
        double bizDensity = estimateBizDensity(text, preConfig);
        int estBase = (int) (turns * bizDensity * 0.3 * 100);
        int estMin = Math.max(0, estBase - 3);
        int estMax = estBase + 8;

        // 检测场景
        List<String> scenes = detectScenes(text, preConfig);

        log.info("预检完成 score={} grade={} estGrains={}-{} scenes={}", overallScore, grade, estMin, estMax, scenes);
        return new PreCheckResult(overallScore, grade, estMin, estMax, scenes, allChecks);
    }

    // ── helpers ──

    private double averageScore(List<CheckItem> items, String dimension) {
        return items.stream()
                .filter(i -> dimension.equals(i.dimension()))
                .mapToInt(CheckItem::score)
                .average().orElse(50);
    }

    private int countDialogueTurns(String text, String roleLabel, String counterpartyLabel) {
        int count = 0;
        String lastRole = "";
        for (String line : text.split("\n")) {
            String t = line.trim();
            String role = null;
            if (t.startsWith(counterpartyLabel)) role = "c";
            else if (t.startsWith(roleLabel) || t.startsWith("我")) role = "s";
            else if (t.matches("^[\\u4e00-\\u9fa5]{1,4}[：:].*")) role = "x";
            if (role != null && !role.equals(lastRole)) { count++; lastRole = role; }
        }
        return count / 2;
    }

    private double estimateBizDensity(String text, DomainConfig.PreCheckConfig config) {
        var groups = config.getKeywordGroups();
        { if (groups == null || groups.isEmpty()) return 0.01; }
        int hits = 0;
        for (var group : groups) {
            for (String kwList : group.getKeywords()) {
                for (String kw : kwList.split("[,\\s]+")) {
                    if (!kw.isBlank() && text.contains(kw.trim())) hits++;
                }
            }
        }
        return (double) hits / Math.max(1, text.length());
    }

    private List<String> detectScenes(String text, DomainConfig.PreCheckConfig config) {
        var mappings = config.getSceneMapping();
        { if (mappings == null || mappings.isEmpty()) return List.of(); }
        List<String> found = new ArrayList<>();
        for (var mapping : mappings) {
            if (mapping.getKeywords() != null) {
                for (String kw : mapping.getKeywords()) {
                    if (text.contains(kw)) { found.add(mapping.getScene()); break; }
                }
            }
        }
        return found;
    }

    private List<String> getExistingGrainTexts(UUID spaceId) {
        try {
            return grainRepository.findBySpaceId(spaceId).stream()
                    .map(g -> (g.getSceneDescription() != null ? g.getSceneDescription() : "")
                            + (g.getExpertThought() != null ? g.getExpertThought() : ""))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private double textSimilarity(String a, String b) {
        Set<String> na = ngrams(a, 5);
        Set<String> nb = ngrams(b, 5);
        if (na.isEmpty() && nb.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(na);
        intersection.retainAll(nb);
        Set<String> union = new HashSet<>(na);
        union.addAll(nb);
        return (double) intersection.size() / union.size();
    }

    private Set<String> ngrams(String text, int n) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i <= text.length() - n; i++) {
            result.add(text.substring(i, i + n));
        }
        return result;
    }

    private String pct(double d) {
        return String.format("%.0f%%", d * 100);
    }
}
