package com.aiextract.common;

import java.util.Locale;
import java.util.Set;

/**
 * 上传限制的单一真相源 —— 文件大小与扩展名。
 *
 * <p>知识库、案例库、分身素材三个入口此前各自写死上限与格式清单，导致
 * 页面提示 50MB、后端实际 20MB，以及前端可选 .doc/.xlsx 但后端拒绝。
 * 统一从这里取，页面文案与后端校验保持一致。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-14
 */
public final class UploadLimits {

    /** 单文件上限（字节） */
    public static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    /** 单文件上限（MB），用于提示文案 */
    public static final int MAX_FILE_SIZE_MB = 20;

    /** 分身素材：文档 + 图片 + 音频 */
    public static final Set<String> MATERIAL_EXTENSIONS = Set.of(
            ".pdf", ".docx", ".pptx", ".txt", ".md", ".csv", ".html", ".htm",
            ".mp3", ".wav", ".jpg", ".jpeg", ".png");

    /** 知识库 / 案例库：只收文档，不收图片音频 */
    public static final Set<String> DOCUMENT_EXTENSIONS = Set.of(
            ".pdf", ".docx", ".txt", ".md", ".pptx", ".csv", ".html", ".htm");

    private UploadLimits() {
    }

    /** 提取小写扩展名；无扩展名返回空串 */
    public static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot < 0 ? "" : lower.substring(dot);
    }

    public static boolean isMaterialAllowed(String fileName) {
        return MATERIAL_EXTENSIONS.contains(extensionOf(fileName));
    }

    public static boolean isDocumentAllowed(String fileName) {
        return DOCUMENT_EXTENSIONS.contains(extensionOf(fileName));
    }

    /** 用于错误提示：pdf/docx/pptx/txt/md/csv/html */
    public static String documentExtensionHint() {
        return "pdf/docx/pptx/txt/md/csv/html";
    }

    /** 用于错误提示：pdf/docx/pptx/txt/md/csv/html/mp3/wav/jpg/png */
    public static String materialExtensionHint() {
        return "pdf/docx/pptx/txt/md/csv/html/mp3/wav/jpg/png";
    }
}
