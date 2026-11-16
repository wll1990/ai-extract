package com.aiextract.service;

import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 内容准入质量门禁 —— 知识条目与案例进入库前的硬性标准。
 *
 * <p>为什么需要：分身回答质量直接取决于检索到的知识/案例质量。用户上传整本书或一批
 * 未整理文档时，如果不设门槛，会让「版权页」「目录」「一句话便签」「空壳案例」进库，
 * 稀释检索、占满审核队列。故在三个位置统一校验：</p>
 * <ol>
 *   <li>萃取落草稿时：不达标的草稿打上 issues，不允许直接采纳；</li>
 *   <li>人工新增/编辑时：阻断明显无效内容；</li>
 *   <li>审批生效时：再校验一次，防止绕过。</li>
 * </ol>
 *
 * <p>分级：{@code issues} 阻断（必须补齐），{@code warnings} 提醒（可生效但建议补）。
 * 书籍来源（book）标准更严：要求出处、限制正文长度、禁止版权页/序言残留。</p>
 */
public final class ContentQualityGate {

    /** 书籍/课程来源的知识卡片长度上限：太长说明是在抄原文，不是提炼 */
    public static final int BOOK_KNOWLEDGE_MAX_CONTENT = 600;
    public static final int BOOK_KNOWLEDGE_MIN_CONTENT = 60;

    /** 普通知识条目长度：下限防便签，上限防整章粘贴 */
    public static final int KNOWLEDGE_MIN_CONTENT = 10;
    public static final int KNOWLEDGE_SUGGESTED_CONTENT = 40;
    public static final int KNOWLEDGE_MAX_CONTENT = 1500;

    public static final int TITLE_MAX = 60;
    public static final int TITLE_MIN = 4;

    private static final Pattern BOOK_RESIDUE = Pattern.compile(
            "版权页|图书在版编目|ISBN|推荐序|作者简介|出版发行|印次|定价|版权所有");
    private static final Pattern CHAPTER_ONLY = Pattern.compile("^第[一二三四五六七八九十百零\\d]+[章节].{0,20}$");
    private static final Pattern PLACEHOLDER_CASE = Pattern.compile("^(案例|示例|例子|案例[一二三四五六七八九十\\d]+)$");
    private static final Pattern HAS_NUMBER = Pattern.compile("[0-9一二三四五六七八九十百千万]+");
    private static final Pattern HAS_BOUNDARY = Pattern.compile("适用|不适用|前提|边界|条件|场景|除非|仅当");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContentQualityGate() {
    }

    /** 质量报告：score 0-100，issues 阻断，warnings 提醒 */
    public record QualityReport(int score, List<String> issues, List<String> warnings) {
        public boolean pass() {
            return issues.isEmpty();
        }

        public String issueText() {
            return String.join("；", issues);
        }

        public String warningText() {
            return String.join("；", warnings);
        }
    }

    /**
     * 知识条目校验。
     *
     * @param bookMode 书籍来源（更严：要求出处、长度上限更小）
     */
    public static QualityReport checkKnowledge(String title, String content, String category,
                                               String source, boolean bookMode) {
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int score = 60;

        String t = title == null ? "" : title.trim();
        String c = content == null ? "" : content.trim();

        if (t.isEmpty()) {
            issues.add("标题为空");
        } else if (t.length() > TITLE_MAX) {
            issues.add("标题超过 " + TITLE_MAX + " 字");
        } else if (bookMode && t.length() < 6) {
            issues.add("书籍知识卡片标题过短（至少 6 字）");
        } else if (!bookMode && t.length() < 2) {
            issues.add("标题过短");
        } else if (!bookMode && t.length() < TITLE_MIN) {
            warnings.add("标题偏短，建议写明适用场景");
        }
        if (CHAPTER_ONLY.matcher(t).matches()) {
            issues.add("标题只是章节名，未说明知识内容");
        }
        if (bookMode && BOOK_RESIDUE.matcher(t + c).find()) {
            issues.add("内容含版权页/序言等无效信息");
        }

        int min = bookMode ? BOOK_KNOWLEDGE_MIN_CONTENT : KNOWLEDGE_MIN_CONTENT;
        int max = bookMode ? BOOK_KNOWLEDGE_MAX_CONTENT : KNOWLEDGE_MAX_CONTENT;
        if (c.isEmpty()) {
            issues.add("正文为空");
        } else if (c.length() < min) {
            issues.add("正文过短（至少 " + min + " 字），无法独立检索");
        } else if (c.length() > max) {
            issues.add("正文过长（超过 " + max + " 字），请拆成多条观点");
        } else if (!bookMode && c.length() < KNOWLEDGE_SUGGESTED_CONTENT) {
            warnings.add("正文偏短，建议补充适用条件与具体做法");
        }
        if (!KnowledgeEntry.isValidCategory(category)) {
            issues.add("分类不合法");
        }
        if (bookMode && (source == null || source.isBlank())) {
            issues.add("缺少出处（书名 + 章节）");
        }

        if (!c.isEmpty()) {
            if (HAS_NUMBER.matcher(c).find()) {
                score += 10;
            } else {
                warnings.add("正文没有具体数字，可检索性偏弱");
            }
            if (HAS_BOUNDARY.matcher(c).find()) {
                score += 10;
            } else {
                warnings.add("建议补充适用条件/边界");
            }
        }
        if (hasSceneWord(t)) {
            score += 10;
        } else if (!t.isEmpty()) {
            warnings.add("标题建议写明场景（如「制造业客户说预算未批」）");
        }
        if (bookMode) {
            score += 5;
        }

        return new QualityReport(clamp(score), issues, warnings);
    }

    /**
     * 案例校验。不改写内容，只判断是否具备可检索、可复用的最低信息量。
     */
    public static QualityReport checkCase(String title, String result, String objection,
                                          String customerProfile, String keyActions,
                                          String scripts, String dialogueQuote,
                                          String source, boolean bookMode) {
        List<String> issues = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int score = 60;

        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) {
            issues.add("标题为空");
        } else if (t.length() > TITLE_MAX) {
            issues.add("标题超过 " + TITLE_MAX + " 字");
        } else if (bookMode && t.length() < 6) {
            issues.add("书籍案例标题过短（至少 6 字）");
        } else if (!bookMode && t.length() < 2) {
            issues.add("标题过短");
        } else if (!bookMode && t.length() < TITLE_MIN) {
            warnings.add("标题偏短，建议写明客户与场景");
        }
        if (PLACEHOLDER_CASE.matcher(t).matches()) {
            issues.add("标题是占位名，未说明客户与场景");
        }
        if (!IndustryCase.isValidResult(result)) {
            issues.add("结果必须是 赢单/丢单/搁置");
        }
        if (blank(objection) && blank(keyActions) && blank(scripts)) {
            issues.add("缺少核心异议与关键动作，无法作为案例检索");
        }
        if (bookMode && blank(source)) {
            issues.add("书籍案例必须标注出处（书名 + 章节）");
        }

        if (blank(objection)) {
            warnings.add("未填写核心异议");
        }
        if (blank(customerProfile) || !hasProfileField(customerProfile)) {
            warnings.add("客户画像缺少行业/规模/角色，检索命中率会下降");
        }
        if (blank(dialogueQuote)) {
            warnings.add("没有客户原话，回答时难以引用真实表述");
        }
        if (blank(keyActions) && blank(scripts)) {
            warnings.add("建议补充关键动作或话术");
        }
        if (bookMode) {
            warnings.add("书籍案例属于二手资料，建议核对后使用，不与内部实战案例同等采信");
            score += 5;
        } else {
            score += 10;
        }
        if (!blank(dialogueQuote)) {
            score += 10;
        }
        if (hasProfileField(customerProfile)) {
            score += 10;
        }
        if (!blank(scripts) || !blank(keyActions)) {
            score += 10;
        }

        return new QualityReport(clamp(score), issues, warnings);
    }

    /**
     * 审批前的最小校验：只拦结构性无效内容（空标题/超长正文/非法分类），
     * 不拿长度下限去卡历史数据——完整标准在萃取与人工新建时执行。
     */
    public static List<String> approvalBlockersKnowledge(String title, String content, String category) {
        return approvalBlockersKnowledge(title, content, category, false);
    }

    public static List<String> approvalBlockersKnowledge(String title, String content,
                                                         String category, boolean bookMode) {
        List<String> issues = new ArrayList<>();
        if (blank(title)) {
            issues.add("标题为空");
        } else if (title.trim().length() > TITLE_MAX) {
            issues.add("标题超过 " + TITLE_MAX + " 字");
        }
        if (blank(content)) {
            issues.add("正文为空");
        } else if (content.trim().length() > (bookMode ? BOOK_KNOWLEDGE_MAX_CONTENT : KNOWLEDGE_MAX_CONTENT)) {
            issues.add("正文超过 " + (bookMode ? BOOK_KNOWLEDGE_MAX_CONTENT : KNOWLEDGE_MAX_CONTENT)
                    + " 字，请拆分成多条知识");
        }
        if (!KnowledgeEntry.isValidCategory(category)) {
            issues.add("分类不合法");
        }
        return issues;
    }

    /**
     * 案例审批前校验：萃取阶段记录的结构问题必须补齐；同时拦非法结果。
     */
    public static List<String> approvalBlockersCase(String title, String result, String storedIssues) {
        List<String> issues = new ArrayList<>();
        if (blank(title)) {
            issues.add("标题为空");
        } else if (title.trim().length() > TITLE_MAX) {
            issues.add("标题超过 " + TITLE_MAX + " 字");
        }
        // 结果可以留到草稿阶段再补，但审批生效前必须有结论，否则案例无法用于检索判断
        if (blank(result)) {
            issues.add("审批前必须选择结果：赢单/丢单/搁置");
        } else if (!IndustryCase.isValidResult(result)) {
            issues.add("结果必须是 赢单/丢单/搁置");
        }
        if (!blank(storedIssues)) {
            for (String part : storedIssues.split("；")) {
                if (!part.isBlank()) {
                    issues.add(part.trim());
                }
            }
        }
        return issues;
    }

    private static boolean hasProfileField(String customerProfile) {
        if (blank(customerProfile)) {
            return false;
        }
        try {
            JsonNode node = MAPPER.readTree(customerProfile.trim());
            if (!node.isObject()) {
                return false;
            }
            for (String key : List.of("industry", "scale", "role")) {
                JsonNode value = node.get(key);
                if (value != null && !value.asText().isBlank()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            // 普通文本画像（旧数据）按「有画像」处理，不阻断
            return true;
        }
    }

    private static boolean hasSceneWord(String title) {
        return title.contains("客户") || title.contains("场景") || title.contains("行业")
                || title.contains("异议") || title.contains("报价") || title.contains("竞品")
                || title.contains("预算") || title.contains("决策") || title.contains("续约")
                || title.contains("回款") || title.contains("采购");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static int clamp(int score) {
        return Math.max(0, Math.min(100, score));
    }
}
