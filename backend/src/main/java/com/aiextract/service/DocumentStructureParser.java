package com.aiextract.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文档结构解析器 —— 从纯文本识别章节层级，产出「节」级分块单元（结构感知分块）。
 *
 * <p>纯文本来自 ai-service 文件解析（可能已丢失字号/加粗等格式），故靠编号模式 + 行特征
 * 识别标题层级：编号深度决定层级——「第X章 / 一、」一级，「X.Y」二级，「X.Y.Z /（一）」三级。
 * 误判不致命（fail-soft）：被误判为正文的标题只会与相邻段落合并，抽取内容仍完整，仅溯源少一级。</p>
 *
 * <p>整本书萃取即基于此：分块单元从「固定字数」升级为「一个节 + 标题链上下文」，每条知识/案例的
 * source 记「章·节」路径，为引用溯源与过期批量更新打基础。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
public final class DocumentStructureParser {

    /** 标题行最大长度，超过视为正文 */
    private static final int MAX_HEADING_LENGTH = 60;

    /** 单块最大字符数（超长节按此切分，相邻块重叠一段） */
    public static final int MAX_CHUNK_CHARS = 3000;

    /** 句末标点结尾 → 视为正文，不是标题 */
    private static final Pattern SENTENCE_END = Pattern.compile(".*[。！？；，、：]$");

    private DocumentStructureParser() {
    }

    /** 文档结构单元：level 标题深度，chain 标题链（"第3章 竞品分析 / 3.2 价格对比"），content 正文 */
    public record Section(int level, String chain, String content) {
    }

    /** 识别到的标题：level 层级，title 标题文本 */
    public record Heading(int level, String title) {
    }

    /**
     * 解析文本为节列表。正文在第一个标题之前的部分归入 level=0、chain 为空的「序言」节；
     * 无任何标题时返回单个 level=0 节。
     */
    public static List<Section> parseSections(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> paragraphs = rawParagraphs(text);
        List<Section> sections = new ArrayList<>();
        List<String> body = new ArrayList<>();
        List<String> titleStack = new ArrayList<>();
        int currentLevel = 0;

        for (String p : paragraphs) {
            Heading h = detectHeading(p);
            if (h == null) {
                body.add(p);
                continue;
            }
            if (!body.isEmpty()) {
                sections.add(toSection(currentLevel, titleStack, body));
                body = new ArrayList<>();
            }
            while (titleStack.size() >= h.level()) {
                titleStack.remove(titleStack.size() - 1);
            }
            titleStack.add(h.title());
            currentLevel = h.level();
        }
        if (!body.isEmpty()) {
            sections.add(toSection(currentLevel, titleStack, body));
        }
        return sections;
    }

    /**
     * 把超长节的正文切成不超过 maxChars 的块，相邻块重叠一段（避免横跨边界的知识被切断）。
     * 单个超长段落不切（无法在段内安全切分）。
     */
    public static List<String> splitSection(String content, int maxChars) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        if (content.length() <= maxChars) {
            return List.of(content);
        }
        String[] paras = content.split("\n\n");
        List<String> chunks = new ArrayList<>();
        int i = 0;
        while (i < paras.length) {
            StringBuilder sb = new StringBuilder(paras[i]);
            int j = i + 1;
            while (j < paras.length && sb.length() + 2 + paras[j].length() <= maxChars) {
                sb.append("\n\n").append(paras[j]);
                j++;
            }
            chunks.add(sb.toString());
            if (j >= paras.length) {
                break;
            }
            // 下一块起点：本块含多段则回退一段（重叠），只含一段则前进（无法重叠），保证 i 严格递增不死循环
            i = Math.max(j - 1, i + 1);
        }
        return chunks;
    }

    /** 便捷重载：用 {@link #MAX_CHUNK_CHARS} 切分 */
    public static List<String> splitSection(String content) {
        return splitSection(content, MAX_CHUNK_CHARS);
    }

    // ==================== 私有辅助 ====================

    private static List<String> rawParagraphs(String text) {
        String[] raw = text.split("\\n\\s*\\n");
        List<String> result = new ArrayList<>();
        for (String p : raw) {
            String t = p.trim();
            if (!t.isEmpty()) {
                result.add(t);
            }
        }
        return result;
    }

    private static Heading detectHeading(String p) {
        if (p == null) {
            return null;
        }
        String line = p.trim();
        if (line.isEmpty() || line.length() > MAX_HEADING_LENGTH) {
            return null;
        }
        if (line.indexOf('\n') >= 0) {
            return null;
        }
        // markdown 井号标题（剥离井号前缀）
        if (line.startsWith("#")) {
            int i = 0;
            while (i < line.length() && line.charAt(i) == '#') {
                i++;
            }
            if (i >= 1 && i <= 6 && i < line.length() && Character.isWhitespace(line.charAt(i))) {
                return new Heading(i, line.substring(i).trim());
            }
        }
        if (SENTENCE_END.matcher(line).matches()) {
            return null;
        }
        int level = detectLevel(line);
        if (level <= 0) {
            return null;
        }
        return new Heading(level, line);
    }

    private static int detectLevel(String line) {
        if (line.matches("^第[一二三四五六七八九十百零\\d]+[章节].*")) {
            return 1;
        }
        if (line.matches("^[一二三四五六七八九十]+、.*")) {
            return 1;
        }
        if (line.matches("^\\d{1,2}\\.\\d{1,2}\\.\\d{1,2}.*")) {
            return 3;
        }
        if (line.matches("^\\d{1,2}\\.\\d{1,2}.*")) {
            return 2;
        }
        if (line.matches("^\\d{1,2}\\..*")) {
            return 2;
        }
        if (line.matches("^[（(][一二三四五六七八九十]+[）)].*")) {
            return 3;
        }
        return 0;
    }

    private static Section toSection(int level, List<String> titleStack, List<String> body) {
        String chain = String.join(" / ", titleStack);
        String content = String.join("\n\n", body);
        return new Section(level, chain, content);
    }
}
