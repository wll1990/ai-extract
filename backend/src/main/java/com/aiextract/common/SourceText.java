package com.aiextract.common;

/**
 * 来源文本拼装 —— 统一「《文档名/书名》 · 章节」格式，并防止超过列宽。
 *
 * <p>此前只有书籍模式会拼书名，普通文档上传只保留章节链，导致详情页和对话引用
 * 无法回答「这条内容来自哪份文件」。所有上传入口统一经此拼装。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-14
 */
public final class SourceText {

    /** 与 knowledge_entry.source / industry_case.source 的列宽一致 */
    public static final int MAX_LENGTH = 1000;

    /** 与 source_document 列宽一致 */
    public static final int MAX_DOCUMENT_LENGTH = 255;

    private SourceText() {
    }

    /** 来源文档名/书名，按 source_document 列宽截断 */
    public static String truncateDocument(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= MAX_DOCUMENT_LENGTH
                ? trimmed : trimmed.substring(0, MAX_DOCUMENT_LENGTH);
    }

    /**
     * 拼装来源。document 为空时保留原章节链；section 为空时只输出文档名。
     */
    public static String compose(String document, String section) {
        String doc = document == null ? "" : document.trim();
        String sec = section == null ? "" : section.trim();
        if (sec.startsWith("《") || sec.contains("》 · ")) {
            // 已带文档/书名（书籍模式、外部导入），不再叠加前缀
            return truncate(sec);
        }
        String text;
        if (doc.isEmpty()) {
            text = sec;
        } else if (sec.isEmpty()) {
            text = "《" + doc + "》";
        } else {
            text = "《" + doc + "》 · " + sec;
        }
        return truncate(text);
    }

    public static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH);
    }
}
