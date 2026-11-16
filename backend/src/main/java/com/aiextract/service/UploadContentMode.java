package com.aiextract.service;

import java.util.regex.Pattern;

/**
 * 上传素材类型识别：企业自有资料 vs 外部书籍/课程。
 *
 * <p>用户经常直接把整本电子书传进知识库。整本书按普通文档处理会产出大量章级大块、
 * 版权页和叙事段落，检索阈值命中不了，还会挤占审核人力。故上传时先分流：</p>
 * <ul>
 *   <li>{@link #INTERNAL}：客户纪要、产品资料、制度文件 → 现有按节抽取；</li>
 *   <li>{@link #BOOK}：书籍/课程 → 专用提示词，按「方法论卡片」提炼，丢弃版权页与叙事段落。</li>
 * </ul>
 *
 * <p>前端可显式选择；未选择时按文本特征自动判定（长文 + 多章节 + 书籍版式标记）。</p>
 */
public final class UploadContentMode {

    public static final String INTERNAL = "internal";
    public static final String BOOK = "book";

    /** 书籍版式标记：出现 3 个以上视为书籍 */
    private static final Pattern BOOK_MARKERS = Pattern.compile(
            "图书在版编目|ISBN|版权所有|推荐序|作者简介|出版社|定价|第[一二三四五六七八九十\\d]+版|印次");

    /** 短文档不可能是整本书，避免误判 */
    private static final int BOOK_MIN_CHARS = 25_000;
    /** 单看长度（无章节结构）也要足够长 */
    private static final int BOOK_LONG_CHARS = 60_000;

    private UploadContentMode() {
    }

    /** 归一化前端传入的类型；非法值一律按 internal 处理 */
    public static String normalize(String sourceKind) {
        return BOOK.equalsIgnoreCase(sourceKind == null ? "" : sourceKind.trim()) ? BOOK : INTERNAL;
    }

    public static boolean isBook(String sourceKind) {
        return BOOK.equals(normalize(sourceKind));
    }

    /**
     * 自动判定是否书籍：显式选择优先（由调用方处理），此处只看文本。
     *
     * @param fileName     文件名（辅助判断，可为空）
     * @param text         解析后的纯文本
     * @param sectionCount 结构解析出的节数
     */
    public static boolean detectBookLike(String fileName, String text, int sectionCount) {
        if (text == null) {
            return false;
        }
        int length = text.length();
        if (length < BOOK_MIN_CHARS) {
            return false;
        }
        if (length >= BOOK_LONG_CHARS) {
            return true;
        }
        if (sectionCount >= 8) {
            return true;
        }
        int markers = countMarkers(text);
        if (markers >= 3) {
            return true;
        }
        String name = fileName == null ? "" : fileName;
        return markers >= 1 && (name.contains("书") || name.contains("课程") || name.contains("讲义"));
    }

    private static int countMarkers(String text) {
        var matcher = BOOK_MARKERS.matcher(text);
        int count = 0;
        while (matcher.find() && count < 5) {
            count++;
        }
        return count;
    }
}
