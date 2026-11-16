package com.aiextract;

import com.aiextract.service.RetrievalMetricsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetrievalMetricsService 单元测试 —— 金标 vs 实际检索证据的命中匹配与指标口径。
 */
class RetrievalMetricsServiceTest {

    private RetrievalMetricsService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 检回两条：颗粒「异议处理」相似度 0.83（排第 1）、知识「复购率定义」0.70（排第 2）。 */
    private static final String EVIDENCE = """
            {"grains":[{"id":"g1","sceneTag":"异议处理","tier":"high","similarity":0.83}],
             "knowledge":[{"id":"k1","title":"复购率定义","similarity":0.70}],
             "cases":[],"fallbackLevel":0}
            """;

    @BeforeEach
    void setUp() {
        service = new RetrievalMetricsService(objectMapper);
    }

    @Test
    void fullHit_recallAndPrecisionAreOne() {
        RetrievalMetricsService.Metrics m = service.compute("[\"复购率定义\",\"异议处理\"]", EVIDENCE);

        assertEquals(2, m.expectedCount());
        assertEquals(2, m.hitExpected());
        assertEquals(1.0, m.recall(), 1e-9);
        assertEquals(1.0, m.precisionAtK(), 1e-9);
        assertEquals(1.0, m.mrr(), 1e-9);
        assertTrue(m.missedExpected().isEmpty());
    }

    @Test
    void partialHit_recallIsHalf() {
        RetrievalMetricsService.Metrics m = service.compute("[\"复购率定义\",\"报价策略\"]", EVIDENCE);

        assertEquals(1, m.hitExpected());
        assertEquals(0.5, m.recall(), 1e-9);
        assertEquals(0.5, m.precisionAtK(), 1e-9);
        assertEquals(1, m.missedExpected().size());
        assertEquals("报价策略", m.missedExpected().get(0));
    }

    @Test
    void mrr_usesRankOfFirstHit() {
        // 只命中排第 2 的知识条目 → MRR=1/2
        RetrievalMetricsService.Metrics m = service.compute("[\"复购率定义\"]", EVIDENCE);

        assertEquals(0.5, m.mrr(), 1e-9);
    }

    @Test
    void zeroHit_allMetricsZero() {
        RetrievalMetricsService.Metrics m = service.compute("[\"竞品价格策略\"]", EVIDENCE);

        assertEquals(0, m.hitExpected());
        assertEquals(0.0, m.recall(), 1e-9);
        assertEquals(0.0, m.precisionAtK(), 1e-9);
        assertEquals(0.0, m.mrr(), 1e-9);
    }

    @Test
    void emptyEvidence_scoresZeroWithoutThrowing() {
        RetrievalMetricsService.Metrics m = service.compute("[\"复购率定义\"]", "{\"grains\":[],\"knowledge\":[],\"cases\":[]}");

        assertEquals(0, m.retrievedCount());
        assertEquals(0.0, m.recall(), 1e-9);
        assertEquals(0.0, m.mrr(), 1e-9);
    }

    @Test
    void noExpected_returnsNullSoItemIsExcluded() {
        assertNull(service.compute(null, EVIDENCE));
        assertNull(service.compute("[]", EVIDENCE));
    }

    @Test
    void matchingIsNormalized_notExactText() {
        // 金标带空格与标点、检回是标题前缀，仍应命中
        RetrievalMetricsService.Metrics m = service.compute("[\" 复购率 定义 \"]", EVIDENCE);

        assertEquals(1, m.hitExpected());
    }

    @Test
    void attachTo_writesMetricsIntoRuleCheck() throws Exception {
        RetrievalMetricsService.Metrics m = service.compute("[\"复购率定义\"]", EVIDENCE);

        String merged = service.attachTo("{\"forbidden_hit\":false}", m);
        JsonNode node = objectMapper.readTree(merged).path("retrieval_metrics");

        assertEquals(1, node.path("expectedCount").asInt());
        assertEquals(0.5, node.path("mrr").asDouble(), 1e-9);
        assertTrue(node.path("missedExpected").isArray());
    }

    @Test
    void attachTo_withNullMetricsKeepsRuleCheckUntouched() {
        assertEquals("{\"forbidden_hit\":false}", service.attachTo("{\"forbidden_hit\":false}", null));
    }
}
