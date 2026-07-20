package com.aiextract.service.precheck;

import com.aiextract.config.DomainConfig.PreCheckConfig;
import java.util.*;

/**
 * 质量维度分析器 — 检查素材的语言质量和内容纯净度。
 *
 * <p>三项检查：噪音比、中文占比、重复检测。</p>
 */
public class QualityAnalyzer {

    /**
     * @param text   清洗后文本
     * @param config 预检配置
     * @return 质量维度的检查项列表
     */
    public List<CheckItem> analyze(String text, PreCheckConfig config) {
        List<CheckItem> items = new ArrayList<>();
        items.add(checkNoiseRatio(text, config));
        items.add(checkChineseRatio(text, config));
        // duplicateCheck 需要访问 DB，由 MaterialPreChecker 单独处理
        return items;
    }

    /**
     * 重复检测 — 需要已保存颗粒列表，在 MaterialPreChecker 中调用。
     */
    public CheckItem checkDuplicate(String text, List<String> existingGrainTexts, double maxSimilarity) {
        if (existingGrainTexts == null || existingGrainTexts.isEmpty()) {
            return new CheckItem("quality", "duplicate", true, 100, "无已有颗粒，跳过重复检测", null);
        }
        double maxSim = 0;
        String mostSimilar = "";
        for (String existing : existingGrainTexts) {
            double sim = textSimilarity(text, existing);
            if (sim > maxSim) {
                maxSim = sim;
                mostSimilar = existing;
            }
        }
        if (maxSim > maxSimilarity) {
            String preview = mostSimilar.length() > 50 ? mostSimilar.substring(0, 50) + "..." : mostSimilar;
            return new CheckItem("quality", "duplicate", false, Math.max(0, (int) ((1 - maxSim) * 100)),
                    "与已有素材高度重复（相似度 " + pct(maxSim) + "）",
                    "该内容与已有素材「" + preview + "」高度相似，请勿重复上传");
        } else if (maxSim > maxSimilarity * 0.7) {
            return new CheckItem("quality", "duplicate", true, 60,
                    "与已有素材有 " + pct(maxSim) + " 相似，可能存在部分重复", null);
        } else {
            return new CheckItem("quality", "duplicate", true, 100,
                    "未检测到重复内容（与已有素材最高相似度 " + pct(maxSim) + "）", null);
        }
    }

    private CheckItem checkNoiseRatio(String text, PreCheckConfig config) {
        var patterns = config.getNoisePatterns();
        if (patterns == null) {
            return new CheckItem("quality", "noise_ratio", true, 60, "未配置噪音模式，跳过检查", null);
        }
        int noiseChars = 0;
        List<String> allNoise = new ArrayList<>();
        addAllIfNotNull(allNoise, patterns.getGreeting());
        addAllIfNotNull(allNoise, patterns.getChitchat());
        addAllIfNotNull(allNoise, patterns.getEcho());
        addAllIfNotNull(allNoise, patterns.getClosing());
        addAllIfNotNull(allNoise, patterns.getFiller());

        String normalized = text.replaceAll("[，。！？、；：\"\"''\\s]", "");
        for (String noise : allNoise) {
            String noiseNorm = noise.replaceAll("[，。！？、；：\"\"''\\s]", "");
            int idx = 0;
            while ((idx = normalized.indexOf(noiseNorm, idx)) != -1) {
                noiseChars += noiseNorm.length();
                idx += noiseNorm.length();
            }
        }
        double noiseRatio = (double) noiseChars / Math.max(1, text.length());
        double threshold = config.getNoiseRatioMax();
        if (noiseRatio <= threshold * 0.5) {
            return new CheckItem("quality", "noise_ratio", true, Math.min(100, 100 - (int) (noiseRatio * 100)),
                    "噪音占比 " + pct(noiseRatio) + "，内容质量良好", null);
        } else if (noiseRatio <= threshold) {
            return new CheckItem("quality", "noise_ratio", true, Math.max(30, (int) ((1 - noiseRatio / threshold) * 70)),
                    "噪音占比 " + pct(noiseRatio) + "，可接受", "建议裁剪部分寒暄和无关闲聊");
        } else {
            return new CheckItem("quality", "noise_ratio", false, Math.max(5, (int) ((1 - noiseRatio / threshold) * 50)),
                    "无效内容占比 " + pct(noiseRatio) + "，超过阈值 " + pct(threshold),
                    "建议裁剪寒暄、口头禅等非实质内容后重新上传");
        }
    }

    private CheckItem checkChineseRatio(String text, PreCheckConfig config) {
        double ratio = chineseRatio(text);
        double warn = config.getChineseRatioWarn();
        if (ratio >= warn) {
            return new CheckItem("quality", "chinese_ratio", true, Math.min(100, (int) (ratio * 100)),
                    "中文占比 " + pct(ratio) + "，语言正常", null);
        } else if (ratio >= 0.70) {
            return new CheckItem("quality", "chinese_ratio", true, 50,
                    "中文占比 " + pct(ratio) + "，存在较多非中文内容，可能影响萃取质量", "建议以中文内容为主");
        } else {
            return new CheckItem("quality", "chinese_ratio", false, Math.max(10, (int) (ratio * 100)),
                    "中文占比仅 " + pct(ratio) + "，低于建议阈值 " + pct(warn), "当前仅支持中文销售对话分析");
        }
    }

    // ── helpers ──

    private double chineseRatio(String text) {
        { if (text == null || text.isEmpty()) return 0; }
        int chinese = 0;
        for (char c : text.toCharArray()) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                chinese++;
            }
        }
        return (double) chinese / text.length();
    }

    private double textSimilarity(String a, String b) {
        Set<String> ngramsA = ngrams(a, 5);
        Set<String> ngramsB = ngrams(b, 5);
        if (ngramsA.isEmpty() && ngramsB.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(ngramsA);
        intersection.retainAll(ngramsB);
        Set<String> union = new HashSet<>(ngramsA);
        union.addAll(ngramsB);
        return (double) intersection.size() / union.size();
    }

    private Set<String> ngrams(String text, int n) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i <= text.length() - n; i++) {
            result.add(text.substring(i, i + n));
        }
        return result;
    }

    private void addAllIfNotNull(List<String> target, List<String> source) {
        if (source != null) {

            target.addAll(source);

        }
    }

    private String pct(double d) {
        return String.format("%.0f%%", d * 100);
    }
}
