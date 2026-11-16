package com.aiextract.service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 估算系数校准器。
 *
 * <p>调用前无法拿到精确 token，只能用字符数估算；调用后如果 LLM 返回了真实
 * Usage，就用「字符数 / 真实 promptTokens」反推本模型的 chars-per-token，
 * 用 EWMA 平滑后供下一轮上下文裁剪使用。</p>
 *
 * <p>按模型分别记录，同时维护一个全局均值；模型未知时使用全局均值。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
public final class TokenEstimationCalibrator {

    /** 中文为主的初始经验值：1 token ≈ 1.5 字符 */
    private static final double DEFAULT_CHARS_PER_TOKEN = 1.5;
    /** EWMA 平滑系数：单次样本权重，避免个别异常值带偏 */
    private static final double ALPHA = 0.3;
    /** 合理区间外的样本直接丢弃（可能是 usage 缺失或字符统计异常） */
    private static final double MIN_CHARS_PER_TOKEN = 0.5;
    private static final double MAX_CHARS_PER_TOKEN = 6.0;
    private static final String GLOBAL_KEY = "__global__";

    private static final ConcurrentHashMap<String, Double> FACTORS = new ConcurrentHashMap<>();

    private TokenEstimationCalibrator() {
    }

    /** 全局校准系数（模型未知时使用） */
    public static double charsPerToken() {
        return FACTORS.getOrDefault(GLOBAL_KEY, DEFAULT_CHARS_PER_TOKEN);
    }

    /** 指定模型的校准系数 */
    public static double charsPerToken(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return charsPerToken();
        }
        return FACTORS.getOrDefault(modelName, charsPerToken());
    }

    /**
     * 用一次真实调用校准系数。
     *
     * @param modelName    模型名（可空）
     * @param promptChars  发请求前统计的 prompt 字符数
     * @param promptTokens LLM 返回的真实 prompt token 数
     */
    public static void observe(String modelName, int promptChars, int promptTokens) {
        if (promptChars <= 0 || promptTokens <= 0) {
            return;
        }
        double observed = (double) promptChars / promptTokens;
        if (observed < MIN_CHARS_PER_TOKEN || observed > MAX_CHARS_PER_TOKEN) {
            return;
        }
        update(GLOBAL_KEY, observed);
        if (modelName != null && !modelName.isBlank()) {
            update(modelName, observed);
        }
    }

    private static void update(String key, double observed) {
        FACTORS.merge(key, observed, (old, value) -> old * (1 - ALPHA) + value * ALPHA);
    }

    /** 测试用：清空所有校准样本 */
    public static void reset() {
        FACTORS.clear();
    }
}
