package com.aiextract.service.precheck;

import com.aiextract.config.DomainConfig.PreCheckConfig;
import java.util.*;

/**
 * 结构维度分析器 — 检查素材的基础结构质量。
 *
 * <p>三项检查：最小长度、对话轮数、对方发言占比。
 * 角色标签从领域配置注入，不硬编码具体角色名。</p>
 */
public class StructureAnalyzer {

    /** 素材最小长度阈值（字符数），低于此值视为过短 */
    private static final int MIN_LENGTH = 100;
    /** 充分长度 = 3 倍最小阈值 */
    private static final int FULL_LENGTH = MIN_LENGTH * 3;
    /** 偏短但可用的底线 = 最小阈值的一半 */
    private static final int SHORT_LENGTH = MIN_LENGTH / 2;

    /**
     * @param text              清洗前原始文本
     * @param config            预检配置（来自 domain YAML）
     * @param roleLabel         专家角色标签（如"销冠""分析师"）
     * @param counterpartyLabel 对方角色标签（如"客户""市场"）
     */
    public List<CheckItem> analyze(String text, PreCheckConfig config, String roleLabel, String counterpartyLabel) {
        List<CheckItem> items = new ArrayList<>();
        items.add(checkMinLength(text));
        items.add(checkDialogueTurns(text, config, roleLabel, counterpartyLabel));
        items.add(checkCustomerRatio(text, config, counterpartyLabel));
        return items;
    }

    private CheckItem checkMinLength(String text) {
        int len = text.length();

        if (len >= FULL_LENGTH) {
            return new CheckItem("structure", "min_length", true, 100,
                    "内容长度 " + len + " 字，非常充分", null);
        } else if (len >= MIN_LENGTH) {
            return new CheckItem("structure", "min_length", true, 75,
                    "内容长度 " + len + " 字，符合要求", null);
        } else if (len >= SHORT_LENGTH) {
            return new CheckItem("structure", "min_length", true, 50,
                    "内容长度 " + len + " 字，偏短但可用", "建议上传 ≥" + MIN_LENGTH + " 字的完整内容");
        } else {
            return new CheckItem("structure", "min_length", false, Math.max(0, len * 100 / MIN_LENGTH),
                    "内容过短（仅 " + len + " 字）", "建议上传 ≥" + SHORT_LENGTH + " 字的内容");
        }
    }

    private CheckItem checkDialogueTurns(String text, PreCheckConfig config,
                                          String roleLabel, String counterpartyLabel) {
        int minTurns = config.getMinDialogueTurns();
        if (minTurns <= 0) {
            return new CheckItem("structure", "dialogue_turns", true, 100,
                    "当前领域不要求对话结构，跳过检测", null);
        }

        int turns = countRoleAlternations(text, roleLabel, counterpartyLabel);
        if (turns >= minTurns) {
            int score = Math.min(100, 60 + turns * 10);
            return new CheckItem("structure", "dialogue_turns", true, score,
                    "检测到 " + turns + " 轮" + roleLabel + "-" + counterpartyLabel + "对话，结构完整", null);
        } else if (turns > 0) {
            return new CheckItem("structure", "dialogue_turns", false, Math.max(10, turns * 20),
                    "仅检测到 " + turns + " 轮对话，建议至少 " + minTurns + " 轮",
                    "建议上传更完整的" + counterpartyLabel + "-" + roleLabel + "互动记录");
        } else {
            return new CheckItem("structure", "dialogue_turns", false, 0,
                    "未检测到对话结构（可能为独白/心得，系统将按经验分享处理）", null);
        }
    }

    private CheckItem checkCustomerRatio(String text, PreCheckConfig config, String counterpartyLabel) {
        double minRatio = config.getMinCustomerRatio();
        if (minRatio <= 0) {
            return new CheckItem("structure", "customer_ratio", true, 100,
                    "当前领域不要求" + counterpartyLabel + "发言占比，跳过检测", null);
        }

        int counterpartyChars = countCounterpartyChars(text, counterpartyLabel);
        int totalChars = text.replaceAll("\\s", "").length();
        double ratio = totalChars > 0 ? (double) counterpartyChars / totalChars : 0;
        if (ratio >= minRatio) {
            int score = Math.min(100, (int) (ratio * 300));
            return new CheckItem("structure", "customer_ratio", true, score,
                    counterpartyLabel + "发言占比 " + pct(ratio) + "，对话结构均衡", null);
        } else if (ratio > 0.05) {
            return new CheckItem("structure", "customer_ratio", false, (int) (ratio * 500),
                    counterpartyLabel + "发言仅占 " + pct(ratio) + "，建议 ≥" + pct(minRatio),
                    "需要" + counterpartyLabel + "真实的反馈才能萃取有效经验");
        } else {
            return new CheckItem("structure", "customer_ratio", false, 0,
                    "未检测到" + counterpartyLabel + "发言（可能为独白/经验分享）", null);
        }
    }

    // ── helpers ──

    /**
     * 检测角色交替轮数。用配置的 roleLabel + counterpartyLabel 匹配，
     * 保留 "我" 作为第一人称通用标记和正则回退。
     */
    private int countRoleAlternations(String text, String roleLabel, String counterpartyLabel) {
        int count = 0;
        String[] lines = text.split("\n");
        String lastRole = "";
        for (String line : lines) {
            String trimmed = line.trim();
            String role = matchRole(trimmed, counterpartyLabel, roleLabel);
            if (role != null && !role.equals(lastRole)) {
                count++;
                lastRole = role;
            }
        }
        return count / 2; // 每两次角色切换 = 1 轮对话
    }

    /**
     * 统计对方角色的发言字数。用配置的 counterpartyLabel 匹配。
     */
    private int countCounterpartyChars(String text, String counterpartyLabel) {
        int count = 0;
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith(counterpartyLabel)) {
                count += trimmed.replaceAll("^.{1,4}[：:]", "").length();
            }
        }
        return count;
    }

    /**
     * 匹配当前行的角色。优先级：配置的 counterpartyLabel → 配置的 roleLabel → "我" → 正则回退。
     */
    private String matchRole(String trimmed, String counterpartyLabel, String roleLabel) {
        // 对方角色
        if (trimmed.startsWith(counterpartyLabel)) return "counterparty";
        // 专家角色
        if (trimmed.startsWith(roleLabel) || trimmed.startsWith("我")) {
            return "expert";
        }
        // 正则回退：匹配 "xxx：" 格式的任意角色名
        if (trimmed.matches("^[\\u4e00-\\u9fa5]{1,4}[：:].*")) {
            String prefix = trimmed.replaceAll("[：:].*", "");
            if (prefix.length() <= 4) {
                return prefix;
            }
        }
        return null;
    }

    private String pct(double d) {
        return String.format("%.0f%%", d * 100);
    }
}
