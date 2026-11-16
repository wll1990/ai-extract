package com.aiextract;

import com.aiextract.model.ChatChunk;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatChunk 溯源事件单元测试 —— 三类溯源（经验/知识/案例）向后兼容。
 * 验证新工厂方法序列化 sourceType/sourceTitle，且旧版 grain 溯源字段不受影响。
 */
class ChatChunkTest {

    @Test
    void knowledgeSource_serializesSourceTypeAndTitle() {
        ChatChunk c = ChatChunk.knowledgeSource("BANT 四问法", "销售手册 第3章", 0.85);
        assertEquals("source", c.getType());
        assertEquals("knowledge", c.getSourceType());
        assertEquals("BANT 四问法", c.getSourceTitle());
        assertEquals("85", c.getAvgSimilarity());

        String json = c.toSseJson();
        assertTrue(json.contains("\"sourceType\":\"knowledge\""));
        assertTrue(json.contains("\"sourceTitle\":\"BANT 四问法\""));
        assertTrue(json.contains("\"sourceNames\":\"销售手册 第3章\""));
    }

    @Test
    void caseSource_serializesSourceType() {
        ChatChunk c = ChatChunk.caseSource("拿下大单的复盘", "案例集 第2章", 0.72);
        assertEquals("case", c.getSourceType());
        assertEquals("拿下大单的复盘", c.getSourceTitle());
        assertEquals("72", c.getAvgSimilarity());
        assertTrue(c.toSseJson().contains("\"sourceType\":\"case\""));
    }

    @Test
    void legacySource_doesNotSerializeSourceType() {
        // 旧版 grain 溯源工厂不设置 sourceType —— 向后兼容，旧前端视为经验溯源
        ChatChunk c = ChatChunk.source("r1", "报告标题", "g1,g2", "异议处理", 2, "4.5", "88", "素材.mp3");
        assertNull(c.getSourceType());
        assertNull(c.getSourceTitle());
        assertFalse(c.toSseJson().contains("sourceType"));
    }

    @Test
    void fromEventMap_parsesSourceTypeWhenPresent() {
        ChatChunk c = ChatChunk.fromEventMap(Map.of(
                "type", "source", "sourceType", "knowledge", "sourceTitle", "价格政策"));
        assertEquals("source", c.getType());
        assertEquals("knowledge", c.getSourceType());
        assertEquals("价格政策", c.getSourceTitle());
    }

    @Test
    void fromEventMap_absentSourceType_staysNull() {
        ChatChunk c = ChatChunk.fromEventMap(Map.of("type", "source", "grainIds", "g1"));
        assertNull(c.getSourceType());
        assertNull(c.getSourceTitle());
    }
}
