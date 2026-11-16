package com.aiextract;

import com.aiextract.service.DocumentStructureParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档结构解析器单元测试 —— 章节层级识别、标题链、按节分块、超长节重叠切分。纯静态方法。
 */
class DocumentStructureParserTest {

    // ==================== 章节解析 ====================

    @Test
    void parseSections_chapterAndSections() {
        List<DocumentStructureParser.Section> sections = DocumentStructureParser.parseSections(
                "第1章 产品概述\n\n1.1 概述\n\n这是概述正文。\n\n1.2 功能\n\n这是功能正文。");

        assertEquals(2, sections.size());
        assertEquals(2, sections.get(0).level());
        assertEquals("第1章 产品概述 / 1.1 概述", sections.get(0).chain());
        assertEquals("这是概述正文。", sections.get(0).content());
        assertEquals("第1章 产品概述 / 1.2 功能", sections.get(1).chain());
        assertEquals("这是功能正文。", sections.get(1).content());
    }

    @Test
    void parseSections_preambleBeforeFirstHeading() {
        List<DocumentStructureParser.Section> sections = DocumentStructureParser.parseSections(
                "序言正文内容。\n\n第1章 产品\n\n正文。");

        assertEquals(2, sections.size());
        assertEquals(0, sections.get(0).level());
        assertEquals("", sections.get(0).chain());
        assertEquals("序言正文内容。", sections.get(0).content());
        assertEquals(1, sections.get(1).level());
        assertEquals("第1章 产品", sections.get(1).chain());
    }

    @Test
    void parseSections_noHeadings_singleSection() {
        List<DocumentStructureParser.Section> sections = DocumentStructureParser.parseSections(
                "这是没有标题的纯文本。\n\n第二段。");

        assertEquals(1, sections.size());
        assertEquals(0, sections.get(0).level());
        assertEquals("", sections.get(0).chain());
        assertEquals("这是没有标题的纯文本。\n\n第二段。", sections.get(0).content());
    }

    @Test
    void parseSections_chapterResetsChain() {
        List<DocumentStructureParser.Section> sections = DocumentStructureParser.parseSections(
                "第1章 产品\n\n1.1 概述\n\n正文一。\n\n第2章 竞品\n\n2.1 价格\n\n正文二。");

        assertEquals(2, sections.size());
        assertEquals("第1章 产品 / 1.1 概述", sections.get(0).chain());
        assertEquals("第2章 竞品 / 2.1 价格", sections.get(1).chain());
    }

    @Test
    void parseSections_markdownHashStripsPrefix() {
        List<DocumentStructureParser.Section> sections = DocumentStructureParser.parseSections(
                "## 二级标题\n\n正文。");

        assertEquals(1, sections.size());
        assertEquals(2, sections.get(0).level());
        assertEquals("二级标题", sections.get(0).chain());
    }

    // ==================== 分块 ====================

    @Test
    void splitSection_shortSectionReturnsSingleChunk() {
        List<String> chunks = DocumentStructureParser.splitSection("短内容", 3000);
        assertEquals(1, chunks.size());
        assertEquals("短内容", chunks.get(0));
    }

    @Test
    void splitSection_overlongOverlapsLastParagraph() {
        String p1 = "段".repeat(1200);
        String p2 = "落".repeat(1200);
        String p3 = "尾".repeat(1200);
        List<String> chunks = DocumentStructureParser.splitSection(
                p1 + "\n\n" + p2 + "\n\n" + p3, 3000);

        // 每块都应在 3000 字符内，且块数 > 1（被切了）
        assertTrue(chunks.size() > 1);
        for (String c : chunks) {
            assertTrue(c.length() <= 3000, "块长度超限: " + c.length());
        }
        // 相邻块重叠：前一块的结尾段落是后一块的开头段落
        String firstChunk = chunks.get(0);
        String secondChunk = chunks.get(1);
        assertTrue(secondChunk.startsWith(p2), "后一块应以重叠段落开头");
        assertTrue(firstChunk.endsWith(p2), "前一块应以重叠段落结尾");
    }

    @Test
    void splitSection_singleOverlongParagraphNotSplit() {
        String huge = "长".repeat(5000);
        List<String> chunks = DocumentStructureParser.splitSection(huge, 3000);
        assertEquals(1, chunks.size());
        assertEquals(huge, chunks.get(0));
    }

    @Test
    void splitSection_blankReturnsEmpty() {
        assertTrue(DocumentStructureParser.splitSection(null).isEmpty());
        assertTrue(DocumentStructureParser.splitSection("   ").isEmpty());
    }
}
