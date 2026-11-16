package com.aiextract;

import com.aiextract.service.ReviewSegmenter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewSegmenterTest {

    private final ReviewSegmenter segmenter = new ReviewSegmenter();

    @Test
    void detectsCustomerAndSales() {
        List<ReviewSegmenter.SegmentDraft> drafts = segmenter.split(
                "客户：你们多少钱？\n销售：按年付费\n对方: 太贵了\n我：可以先试用");

        assertEquals(4, drafts.size());
        assertEquals("customer", drafts.get(0).speaker());
        assertEquals("你们多少钱？", drafts.get(0).text());
        assertEquals("sales", drafts.get(1).speaker());
        assertEquals("customer", drafts.get(2).speaker());
        assertEquals("sales", drafts.get(3).speaker());
    }

    @Test
    void unknownWhenNoPrefix() {
        List<ReviewSegmenter.SegmentDraft> drafts = segmenter.split("随便说一句");
        assertEquals(1, drafts.size());
        assertEquals("unknown", drafts.get(0).speaker());
        assertEquals("随便说一句", drafts.get(0).text());
    }

    @Test
    void longLineIsSplit() {
        String line = "销售：" + "a".repeat(4500);
        List<ReviewSegmenter.SegmentDraft> drafts = segmenter.split(line);
        assertEquals(3, drafts.size());
        assertTrue(drafts.stream().allMatch(d -> d.text().length() <= 2000));
        assertTrue(drafts.stream().allMatch(d -> "sales".equals(d.speaker())));
    }

    @Test
    void prefixWithoutColonIsNotASpeaker() {
        List<ReviewSegmenter.SegmentDraft> drafts = segmenter.split("我们下周再聊");
        assertEquals("unknown", drafts.get(0).speaker());
    }

    @Test
    void blankLinesIgnored() {
        List<ReviewSegmenter.SegmentDraft> drafts = segmenter.split("\n\n客户：你好\n\n");
        assertEquals(1, drafts.size());
    }
}
