package com.aiextract;

import com.aiextract.service.UploadContentMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上传素材类型识别：整本书应自动切到书籍模式，普通文档不能被误判。
 */
class UploadContentModeTest {

    @Test
    void shortDocument_isNotBook() {
        assertFalse(UploadContentMode.detectBookLike("产品手册.pdf", "产品参数与价格政策。".repeat(50), 3));
    }

    @Test
    void veryLongDocument_isBook() {
        assertTrue(UploadContentMode.detectBookLike("销售资料.pdf", "内容".repeat(35_000), 1));
    }

    @Test
    void manySectionsAndLong_enough_isBook() {
        assertTrue(UploadContentMode.detectBookLike("课程讲义.pdf", "内容".repeat(15_000), 9));
    }

    @Test
    void bookMarkers_detected() {
        String text = ("图书在版编目（CIP）数据\nISBN 978-7-111-00000-0\n版权所有 侵权必究\n"
                + "推荐序\n作者简介\n出版社发行\n" + "正文段落。".repeat(25_000));
        assertTrue(UploadContentMode.detectBookLike("销售.pdf", text, 2));
    }

    @Test
    void normalize_defaultsToInternal() {
        assertEquals(UploadContentMode.INTERNAL, UploadContentMode.normalize(null));
        assertEquals(UploadContentMode.INTERNAL, UploadContentMode.normalize("unknown"));
        assertEquals(UploadContentMode.BOOK, UploadContentMode.normalize(" BOOK "));
    }
}
