package com.aiextract.common;

/**
 * 文件类型口径单一来源 —— 素材 B 端 / C 端、知识、案例都按扩展名归类。
 *
 * <p>不要再用 {@code MultipartFile.getContentType()} 落库：浏览器/代理给的值不稳定，
 * 同一份文件在不同入口会得到不同类型，列表筛选与解析分支会不一致。</p>
 */
public final class FileTypeUtil {

    private FileTypeUtil() {}

    public static String detect(String fileName) {
        if (fileName == null) {
            return "other";
        }
        String name = fileName.toLowerCase();
        if (name.endsWith(".pdf")) return "pdf";
        if (name.endsWith(".doc") || name.endsWith(".docx")) return "doc";
        if (name.endsWith(".txt") || name.endsWith(".md")) return "txt";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "html";
        if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".m4a")
                || name.endsWith(".aac") || name.endsWith(".amr") || name.endsWith(".flac")) {
            return "audio";
        }
        if (name.endsWith(".ppt") || name.endsWith(".pptx")) return "ppt";
        if (name.endsWith(".xls") || name.endsWith(".xlsx") || name.endsWith(".csv")) return "xls";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".gif") || name.endsWith(".webp") || name.endsWith(".bmp")) {
            return "image";
        }
        return "other";
    }
}
