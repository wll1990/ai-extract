package com.aiextract;

import com.aiextract.service.SalesReviewReportParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesReviewReportParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SalesReviewReportParser parser = new SalesReviewReportParser(objectMapper);
    private final UUID segmentId = UUID.randomUUID();

    @Test
    void parsesValidReportAndKeepsUnknownSignalNull() throws Exception {
        String raw = """
                {
                  "summary": "需求挖掘到位",
                  "strengths": [{"point": "先确认现状", "evidence": [{"segmentId": "%s", "quote": "预算 30 万"}], "confidence": "high"}],
                  "weaknesses": [],
                  "risks": [],
                  "customerSignals": [{"field": "预算", "value": "30 万", "sourceType": "unknown", "confidence": "low"}],
                  "nextSteps": [],
                  "leadDraft": {"name": "华星科技"}
                }
                """.formatted(segmentId);

        SalesReviewReportParser.ParsedReport parsed = parser.parse(
                withSummaryEvidence(raw), Set.of(segmentId), Map.of(segmentId, "客户：我们预算 30 万"));

        assertEquals("需求挖掘到位", parsed.summary());
        JsonNode payload = objectMapper.readTree(parsed.payloadJson());
        assertEquals(1, payload.path("strengths").size());
        assertTrue(payload.path("customerSignals").get(0).path("value").isNull());
        assertEquals("华星科技", payload.path("leadDraft").path("name").asText());
    }

    @Test
    void rejectsUnknownSegment() {
        String raw = """
                {"summary":"x","strengths":[{"point":"p","evidence":[{"segmentId":"%s"}]}],
                 "weaknesses":[],"risks":[],"customerSignals":[],"nextSteps":[],"leadDraft":{}}
                """.formatted(UUID.randomUUID());

        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(withSummaryEvidence(raw), Set.of(segmentId), Map.of(segmentId, "原文")));
    }

    @Test
    void dropsMismatchedQuoteButKeepsSegment() throws Exception {
        String raw = """
                {"summary":"x","strengths":[{"point":"p","evidence":[{"segmentId":"%s","quote":"不存在的原话"}]}],
                 "weaknesses":[],"risks":[],"customerSignals":[],"nextSteps":[],"leadDraft":{}}
                """.formatted(segmentId);

        SalesReviewReportParser.ParsedReport parsed = parser.parse(
                withSummaryEvidence(raw), Set.of(segmentId), Map.of(segmentId, "客户：预算 30 万"));

        JsonNode evidence = objectMapper.readTree(parsed.payloadJson())
                .path("strengths").get(0).path("evidence").get(0);
        assertEquals(segmentId.toString(), evidence.path("segmentId").asText());
        assertTrue(evidence.path("quote").isMissingNode() || evidence.path("quote").isNull(),
                "对不上的原文必须丢弃，避免把模型转写当成真实引用");
    }

    @Test
    void rejectsPointWithoutEvidence() {
        String raw = """
                {"summary":"x","strengths":[{"point":"p","evidence":[]}],
                 "weaknesses":[],"risks":[],"customerSignals":[],"nextSteps":[],"leadDraft":{}}
                """;

        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(withSummaryEvidence(raw), Set.of(segmentId), Map.of(segmentId, "原文")));
    }

    @Test
    void rejectsKnownSignalWithoutEvidence() {
        String raw = """
                {"summary":"x","strengths":[],"weaknesses":[],"risks":[],
                 "customerSignals":[{"field":"预算","value":"30 万","sourceType":"customer_words"}],
                 "nextSteps":[],"leadDraft":{}}
                """;

        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(withSummaryEvidence(raw), Set.of(segmentId), Map.of(segmentId, "客户：预算 30 万")));
    }

    @Test
    void missingConfidenceAndSourceTypeFallBackSafely() throws Exception {
        String raw = """
                {"summary":"x","strengths":[{"point":"p","evidence":[{"segmentId":"%s"}]}],
                 "weaknesses":[],"risks":[],"customerSignals":[{"field":"预算","value":"30 万"}],
                 "nextSteps":[],"leadDraft":{}}
                """.formatted(segmentId);

        SalesReviewReportParser.ParsedReport parsed = parser.parse(
                withSummaryEvidence(raw), Set.of(segmentId), Map.of(segmentId, "客户：预算 30 万"));
        JsonNode payload = objectMapper.readTree(parsed.payloadJson());
        assertEquals("low", payload.path("strengths").get(0).path("confidence").asText());
        assertEquals("unknown", payload.path("customerSignals").get(0).path("sourceType").asText());
    }

    @Test
    void stripsMarkdownFence() {
        String raw = """
                ```json
                {"summary":"x","strengths":[],"weaknesses":[],"risks":[],
                 "customerSignals":[],"nextSteps":[],"leadDraft":{}}
                ```
                """;

        SalesReviewReportParser.ParsedReport parsed = parser.parse(
                withSummaryEvidence(raw), Set.of(segmentId), Map.of(segmentId, "原文"));
        assertEquals("x", parsed.summary());
    }

    @Test
    void keepsPrimaryActionAndOnlyRealInfoGaps() throws Exception {
        String raw = """
                {"summary":"x","strengths":[],"weaknesses":[],"risks":[],
                 "customerSignals":[
                   {"field":"预算","value":null,"sourceType":"unknown","confidence":"low"},
                   {"field":"决策人","value":"王总","sourceType":"customer_words","confidence":"high",
                    "evidence":[{"segmentId":"%s"}]}],
                 "primaryAction":{"title":"补一份三年总拥有成本对比","channel":"微信","dueInDays":1,
                   "content":"王总，我把三年总拥有成本按您说的口径算了一版","evidence":[{"segmentId":"%s"}]},
                 "infoGaps":[
                   {"field":"预算","why":"决定方案档位","question":"这个项目预算大概多少？"},
                   {"field":"决策人","why":"已确认，不该出现","question":"最终谁拍板？"}],
                 "nextSteps":[],"leadDraft":{}}
                """.formatted(segmentId, segmentId);

        SalesReviewReportParser.ParsedReport parsed = parser.parse(
                withSummaryEvidence(raw), Set.of(segmentId), Map.of(segmentId, "客户：预算还没定，王总拍板"));
        JsonNode payload = objectMapper.readTree(parsed.payloadJson());

        assertEquals("补一份三年总拥有成本对比",
                payload.path("primaryAction").path("title").asText());
        assertEquals(1, payload.path("infoGaps").size());
        assertEquals("预算", payload.path("infoGaps").get(0).path("field").asText());
        assertEquals("这个项目预算大概多少？",
                payload.path("infoGaps").get(0).path("question").asText());
    }

    @Test
    void fallsBackToFirstNextStepWhenPrimaryActionMissing() throws Exception {
        String raw = """
                {"summary":"x","strengths":[],"weaknesses":[],"risks":[],"customerSignals":[],
                 "nextSteps":[{"title":"确认预算","channel":"电话","dueInDays":5,"content":"问预算"}],
                 "leadDraft":{}}
                """;

        SalesReviewReportParser.ParsedReport parsed = parser.parse(
                withSummaryEvidence(raw), Set.of(segmentId), Map.of(segmentId, "原文"));
        JsonNode payload = objectMapper.readTree(parsed.payloadJson());

        assertEquals("确认预算", payload.path("primaryAction").path("title").asText());
        assertEquals(3, payload.path("primaryAction").path("dueInDays").asInt());
    }

    @Test
    void synthesizesGapQuestionWhenModelOmitsIt() throws Exception {
        String raw = """
                {"summary":"x","strengths":[],"weaknesses":[],"risks":[],
                 "customerSignals":[{"field":"时间线","value":null,"sourceType":"unknown","confidence":"low"}],
                 "nextSteps":[],"leadDraft":{}}
                """;

        SalesReviewReportParser.ParsedReport parsed = parser.parse(
                withSummaryEvidence(raw), Set.of(segmentId), Map.of(segmentId, "原文"));
        JsonNode payload = objectMapper.readTree(parsed.payloadJson());

        assertEquals(1, payload.path("infoGaps").size());
        assertTrue(payload.path("infoGaps").get(0).path("question").asText().contains("启动"));
    }

    /** 报告契约要求一句话结论带引用；测试统一注入一条合法引用 */
    private String withSummaryEvidence(String raw) {
        return raw.replaceFirst("\\{",
                "{\"summaryEvidence\":[{\"segmentId\":\"" + segmentId + "\"}],");
    }

    @Test
    void rejectsSummaryWithoutEvidence() {
        String raw = """
                {"summary":"x","strengths":[],"weaknesses":[],"risks":[],
                 "customerSignals":[],"nextSteps":[],"leadDraft":{}}
                """;

        assertThrows(IllegalArgumentException.class,
                () -> parser.parse(raw, Set.of(segmentId), Map.of(segmentId, "原文")));
    }

    @Test
    void acceptsQuoteSpanningTwoSegments() throws Exception {
        UUID second = UUID.randomUUID();
        String raw = """
                {"summary":"x","summaryEvidence":[{"segmentId":"%s","quote":"前半句后半句"}],
                 "strengths":[],"weaknesses":[],"risks":[],"customerSignals":[],"nextSteps":[],"leadDraft":{}}
                """.formatted(segmentId);

        java.util.Map<UUID, String> ordered = new java.util.LinkedHashMap<>();
        ordered.put(segmentId, "客户：前半句");
        ordered.put(second, "后半句结束");
        SalesReviewReportParser.ParsedReport parsed = parser.parse(
                raw, Set.of(segmentId, second), ordered);
        JsonNode payload = objectMapper.readTree(parsed.payloadJson());
        assertEquals(1, payload.path("summaryEvidence").size());
    }
}
