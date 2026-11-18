package com.aiextract;

import com.aiextract.service.KnowledgeCaseContextService;
import com.aiextract.service.KnowledgeCaseRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识/案例上下文：降级、客户角色字段白名单、教练侧完整打法。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KnowledgeCaseContextServiceTest {

    @Mock private KnowledgeCaseRetriever retriever;

    private KnowledgeCaseContextService service;
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new KnowledgeCaseContextService(retriever, new ObjectMapper());
        ReflectionTestUtils.setField(service, "topK", 3);
        ReflectionTestUtils.setField(service, "knowledgeMinSimilarity", 0.35);
        ReflectionTestUtils.setField(service, "caseMinSimilarity", 0.5);
    }

    @Test
    void blankQuery_skipsEverything() {
        var hits = service.retrieveSafely("  ", companyId, "test");

        assertTrue(hits.knowledge().isEmpty());
        assertTrue(hits.cases().isEmpty());
        verify(retriever, never()).hasAnyActive(any());
        verify(retriever, never()).retrieve(anyString(), any(), anyInt(), anyDouble(), anyDouble());
    }

    @Test
    void noActiveContent_skipsEmbedding() {
        when(retriever.hasAnyActive(companyId)).thenReturn(false);

        var hits = service.retrieveSafely("报价怎么谈", companyId, "test");

        assertTrue(hits.cases().isEmpty());
        verify(retriever, never()).retrieve(anyString(), any(), anyInt(), anyDouble(), anyDouble());
    }

    @Test
    void retrieverFailure_degradesToEmpty() {
        when(retriever.hasAnyActive(companyId)).thenReturn(true);
        when(retriever.retrieve(anyString(), any(), anyInt(), anyDouble(), anyDouble()))
                .thenThrow(new RuntimeException("embedding down"));

        var hits = service.retrieveSafely("报价怎么谈", companyId, "test");

        assertTrue(hits.knowledge().isEmpty());
        assertTrue(hits.cases().isEmpty());
    }

    @Test
    void customerRoleContext_exposesOnlyCustomerSideFields() {
        String ctx = service.buildCustomerRoleContext(List.of(caseHit()));

        assertTrue(ctx.contains("制造业"));
        assertTrue(ctx.contains("戒备 → 认同"));
        assertTrue(ctx.contains("预算没批"));
        // 标准答案不得出现在扮演客户的提示词里
        assertFalse(ctx.contains("拆两期交付"));
        assertFalse(ctx.contains("先锚定年度收益"));
        assertFalse(ctx.contains("可复用"));
        assertFalse(ctx.contains("失败点"));
    }

    @Test
    void coachContext_containsPlaybook() {
        var hits = new KnowledgeCaseRetriever.KnowledgeCaseHits(
                List.of(new KnowledgeCaseRetriever.KnowledgeHit(UUID.randomUUID(), "折扣审批",
                        "50 万以上需副总审批", "policy", "制度", 0.8)),
                List.of(caseHit()));

        String ctx = service.buildCoachContext(hits);

        assertTrue(ctx.contains("折扣审批"));
        assertTrue(ctx.contains("拆两期交付"));
        assertTrue(ctx.contains("先锚定年度收益"));
    }

    @Test
    void sourceFlux_onlyCasesWhenRequested() {
        var hits = new KnowledgeCaseRetriever.KnowledgeCaseHits(
                List.of(new KnowledgeCaseRetriever.KnowledgeHit(UUID.randomUUID(), "知识", "内容",
                        "price", "出处", 0.8)),
                List.of(caseHit()));

        long all = service.buildSourceFlux(hits).count().block();
        long cases = service.buildCaseSourceFlux(hits.cases()).count().block();

        assertTrue(all == 2);
        assertTrue(cases == 1);
    }

    private KnowledgeCaseRetriever.CaseHit caseHit() {
        return new KnowledgeCaseRetriever.CaseHit(UUID.randomUUID(), "制造业预算案例", "价格异议",
                "proposal", "win", "{\"industry\":\"制造业\"}", "戒备 → 认同", "报价 → 试用",
                "预算没批", "拆两期交付", "先锚定年度收益", "分期化解审批", "没确认回款周期",
                "来源", 0.66);
    }
}
