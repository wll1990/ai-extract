package com.aiextract.service.precheck;

import com.aiextract.config.DomainConfig.AcceptanceConfig;
import com.aiextract.config.DomainConfig.PreCheckConfig;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容维度分析器 — 检查素材的业务内容质量。
 *
 * <p>三项检查：业务关键词密度、异议信号、话题多样性。
 * 同时承担准入检查：非销售领域检测、教科书/营销/AI内容检测。</p>
 */
public class ContentAnalyzer {

    /**
     * 准入检查：是否为销售领域内容。
     *
     * @return AcceptanceResult，passed=false 表示拒绝
     */
    public AcceptanceResult checkAcceptance(String text, AcceptanceConfig config) {
        List<String> allKeywords = new ArrayList<>();
        if (config.getSalesKeywords() != null) {
            for (String group : config.getSalesKeywords()) {
                for (String kw : group.split("[,\\s]+")) {
                    { if (!kw.isBlank()) allKeywords.add(kw.trim()); }
                }
            }
        }
        // 销售关键词密度
        int hitCount = 0;
        for (String kw : allKeywords) {
            { if (text.contains(kw)) hitCount++; }
        }
        double density = allKeywords.isEmpty() ? 0 : (double) hitCount / text.length();
        double threshold = config.getSalesKeywordDensityMin();

        if (density < threshold) {
            return AcceptanceResult.reject("NOT_SALES_DOMAIN",
                    "未检测到足够的销售相关关键词（当前 " + pct(density) + "，要求 ≥" + pct(threshold)
                            + "）。本平台专注于销售经验萃取，请上传包含报价、谈判、客户沟通等内容的销售素材",
                    Map.of("salesKeywordDensity", density, "threshold", threshold));
        }

        // 教科书/纯理论检测
        if (config.getFirstPersonRatioMin() != null && config.getFirstPersonRatioMin() > 0) {
            int woCount = countPattern(text, "我");
            double firstPersonRatio = (double) woCount / Math.max(1, text.length());
            int theoreticalCount = countPattern(text, "(应该|需要|必须|要\\.*才能|一定要|切记|记住)");
            double theoreticalRatio = (double) theoreticalCount / Math.max(1, text.length());
            if (firstPersonRatio < config.getFirstPersonRatioMin()
                    && theoreticalRatio > config.getTheoreticalRatioMax()) {
                return AcceptanceResult.reject("TOO_THEORETICAL",
                        "检测到内容偏理论性/教科书风格。我们需要的是真实销售经验——你实际遇到的场景、说过的话、做过的事，而不是'销售应该怎样'的通用建议",
                        Map.of("firstPersonRatio", firstPersonRatio, "theoreticalRatio", theoreticalRatio));
            }
        }

        // 营销广告检测
        if (config.getMarketingSignals() != null && !config.getMarketingSignals().isEmpty()) {
            int marketingHits = 0;
            for (String signal : config.getMarketingSignals()) {
                { if (text.contains(signal)) marketingHits++; }
            }
            double marketingRatio = (double) marketingHits / Math.max(1,
                    text.length() / 10); // 每10字符算1个token
            if (marketingRatio > config.getMarketingRatioMax()) {
                return AcceptanceResult.reject("MARKETING_CONTENT",
                        "检测到内容为营销推广材料，非销售经验分享。请上传真实的销售对话或经验心得",
                        Map.of("marketingRatio", marketingRatio, "threshold", config.getMarketingRatioMax()));
            }
        }

        // 中文占比检测
        if (config.getChineseRatioMin() > 0) {
            double chineseRatio = chineseRatio(text);
            if (chineseRatio < config.getChineseRatioMin()) {
                return AcceptanceResult.reject("NOT_CHINESE",
                        "当前仅支持中文销售素材。检测到中文占比仅 " + pct(chineseRatio) + "，请上传中文内容",
                        Map.of("chineseRatio", chineseRatio, "threshold", config.getChineseRatioMin()));
            }
        }

        // 内容过短
        if (text.length() < config.getMinTextLength()) {
            return AcceptanceResult.reject("TOO_SHORT",
                    "内容过短（仅 " + text.length() + " 字），无法进行有效萃取。请上传 ≥"
                            + config.getMinTextLength() + " 字的完整对话或经验分享",
                    Map.of("textLength", text.length(), "minRequired", config.getMinTextLength()));
        }

        return AcceptanceResult.pass();
    }

    /**
     * 预检内容维度打分。
     */
    public List<CheckItem> analyze(String text, PreCheckConfig config) {
        List<CheckItem> items = new ArrayList<>();
        items.add(checkBusinessDensity(text, config));
        items.add(checkObjectionSignals(text, config));
        items.add(checkTopicVariety(text, config));
        return items;
    }

    private CheckItem checkBusinessDensity(String text, PreCheckConfig config) {
        var groups = config.getKeywordGroups();
        if (groups == null || groups.isEmpty()) {
            return new CheckItem("content", "business_density", true, 50, "未配置业务关键词库，跳过密度检查", null);
        }
        int totalHits = 0;
        for (var group : groups) {
            for (String kwList : group.getKeywords()) {
                for (String kw : kwList.split("[,\\s]+")) {
                    if (!kw.isBlank() && text.contains(kw.trim())) totalHits++;
                }
            }
        }
        double density = (double) totalHits / Math.max(1, text.length());
        double threshold = config.getBusinessDensityThreshold();
        if (density >= threshold) {
            int score = Math.min(100, (int) (density * 4000));
            return new CheckItem("content", "business_density", true, score,
                    "业务关键词密度 " + pct(density) + "，内容丰富", null);
        } else if (density > threshold * 0.5) {
            return new CheckItem("content", "business_density", true, Math.max(30, (int) (density * 4000)),
                    "业务关键词密度 " + pct(density) + "，偏低但可用", "建议补充更多实质业务讨论内容");
        } else {
            return new CheckItem("content", "business_density", false, Math.max(5, (int) (density * 4000)),
                    "业务关键词密度偏低（" + pct(density) + "），可能缺乏实质销售内容",
                    "建议上传包含报价、方案、竞品对比等实质性对话的素材");
        }
    }

    private CheckItem checkObjectionSignals(String text, PreCheckConfig config) {
        var patterns = config.getObjectionPatterns();
        if (patterns == null || patterns.isEmpty()) {
            return new CheckItem("content", "objection_signals", true, 50, "未配置异议信号模式，跳过检查", null);
        }
        List<String> foundSignals = new ArrayList<>();
        for (var pat : patterns) {
            Matcher m = Pattern.compile(pat.getPattern()).matcher(text);
            if (m.find()) foundSignals.add(pat.getLabel());
        }
        if (foundSignals.size() >= 2) {
            return new CheckItem("content", "objection_signals", true, Math.min(100, 50 + foundSignals.size() * 20),
                    "检测到 " + foundSignals.size() + " 个客户异议信号：" + String.join("、", foundSignals), null);
        } else if (foundSignals.size() == 1) {
            return new CheckItem("content", "objection_signals", true, 40,
                    "仅检测到 1 个异议信号（" + foundSignals.get(0) + "），异议类型单一",
                    "建议补充包含多种客户反对意见的对话");
        } else {
            return new CheckItem("content", "objection_signals", false, 0,
                    "未检测到客户异议信号", "客户质疑（如'太贵了''竞品比你们好''我再考虑考虑'）是萃取高质量话术的核心来源");
        }
    }

    private CheckItem checkTopicVariety(String text, PreCheckConfig config) {
        var groups = config.getKeywordGroups();
        if (groups == null || groups.isEmpty()) {
            return new CheckItem("content", "topic_variety", true, 50, "未配置场景分组，跳过话题多样性检查", null);
        }
        List<String> coveredTopics = new ArrayList<>();
        for (var group : groups) {
            for (String kwList : group.getKeywords()) {
                for (String kw : kwList.split("[,\\s]+")) {
                    if (!kw.isBlank() && text.contains(kw.trim())) {
                        coveredTopics.add(group.getName());
                        break;
                    }
                }
            }
        }
        long distinctTopics = coveredTopics.stream().distinct().count();
        if (distinctTopics >= 3) {
            return new CheckItem("content", "topic_variety", true, Math.min(100, 50 + (int) distinctTopics * 15),
                    "覆盖 " + distinctTopics + " 个话题领域：" + String.join("、", coveredTopics.stream().distinct().toList()), null);
        } else if (distinctTopics >= 1) {
            return new CheckItem("content", "topic_variety", true, 30 + (int) distinctTopics * 10,
                    "仅覆盖 " + distinctTopics + " 个话题领域，建议覆盖更多销售阶段",
                    "建议上传覆盖价格谈判、竞品对比、异议处理、需求挖掘等多个话题的素材");
        } else {
            return new CheckItem("content", "topic_variety", false, 0,
                    "未检测到明确的话题领域", "建议上传包含实质性销售讨论的素材");
        }
    }

    // ── helpers ──

    private int countPattern(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        int count = 0;
        { while (m.find()) count++; }
        return count;
    }

    public double chineseRatio(String text) {
        { if (text == null || text.isEmpty()) return 0; }
        int chinese = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                chinese++;
            }
        }
        return (double) chinese / text.length();
    }

    /**
     * 检测 AI 生成信号，返回置信度 0-1。
     */
    public double aiSuspectScore(String text, AcceptanceConfig config) {
        if (config.getAiSignals() == null || config.getAiSignals().isEmpty()) return 0;
        int hits = 0;
        for (String signal : config.getAiSignals()) {
            { if (text.contains(signal)) hits++; }
        }
        return Math.min(1.0, hits * 0.5);
    }

    private String pct(double d) {
        return String.format("%.1f%%", d * 100);
    }
}
