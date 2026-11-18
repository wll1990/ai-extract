package com.aiextract;

import com.aiextract.config.DomainConfigLoader;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.model.Skill;
import com.aiextract.service.ChatStreamAdapter;
import com.aiextract.service.EvaluationAnswerService;
import com.aiextract.service.KnowledgeCaseRetriever;
import com.aiextract.service.PromptAssemblyService;
import com.aiextract.service.RagPipelineService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Assertions;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EvaluationAnswerService 单元测试 —— 验证复用生产检索链路、冻结证据、且不污染知识缺口。
 */
@ExtendWith(MockitoExtension.class)
class EvaluationAnswerServiceTest {

    @Mock
    private RagPipelineService ragPipelineService;
    @Mock
    private KnowledgeCaseRetriever knowledgeCaseRetriever;
    @Mock
    private PromptAssemblyService promptAssembly;
    @Mock
    private ChatStreamAdapter chatStreamAdapter;
    @Mock
    private DomainConfigLoader domainConfigLoader;

    private EvaluationAnswerService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID skillId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final Skill skill = Skill.builder().id(skillId).spaceId(spaceId).type("individual").build();

    @BeforeEach
    void setUp() {
        service = new EvaluationAnswerService(ragPipelineService, knowledgeCaseRetriever,
                promptAssembly, chatStreamAdapter, domainConfigLoader, objectMapper);
        ReflectionTestUtils.setField(service, "ragTopK", 8);
        ReflectionTestUtils.setField(service, "kcTopK", 3);
        ReflectionTestUtils.setField(service, "kcKnowledgeMinSimilarity", 0.35);
        ReflectionTestUtils.setField(service, "kcCaseMinSimilarity", 0.50);
        ReflectionTestUtils.setField(service, "answerTimeoutSeconds", 120);

        when(domainConfigLoader.resolveDomain(skill)).thenReturn("sales.b2b_enterprise");
        when(ragPipelineService.rewriteQuery(anyString(), eq(""), anyString(), eq(skillId)))
                .thenReturn("rewritten-query");
        when(promptAssembly.buildSkillSystemPrompt(any(), anyList(), anyMap(), anyMap(), anyString(), any()))
                .thenReturn("system-prompt");
    }

    @Test
    void answer_returnsAnswerAndFreezesEvidence() throws Exception {
        stubRetrieval();
        stubModel("这是分身的回答");

        EvaluationItem item = EvaluationItem.builder()
                .id(UUID.randomUUID())
                .question("复购率怎么算？")
                .build();

        EvaluationAnswerService.AnswerResult result = service.answer(skill, item, companyId);

        assertEquals("这是分身的回答", result.answer());
        JsonNode evidence = objectMapper.readTree(result.evidenceJson());
        assertEquals(0, evidence.path("fallbackLevel").asInt());
        assertEquals("rewritten-query", evidence.path("ragQuery").asText());
        assertEquals("异议处理", evidence.path("grains").get(0).path("sceneTag").asText());
        assertEquals(0.83, evidence.path("grains").get(0).path("similarity").asDouble(), 1e-9);
        assertEquals("价格手册", evidence.path("knowledge").get(0).path("title").asText());
        assertEquals("某客户续费案例", evidence.path("cases").get(0).path("title").asText());
    }

    @Test
    void answer_retrievalMustNotRecordKnowledgeGap() {
        stubRetrieval();
        stubModel("回答");

        EvaluationItem item = EvaluationItem.builder().id(UUID.randomUUID()).question("问题").build();
        service.answer(skill, item, companyId);

        // 评测题目不是真实用户提问，最后一个参数必须是 false
        verify(ragPipelineService).retrieveGrainsWithScores(
                anyString(), eq(spaceId), anyInt(), anyString(), any(), eq(false));
    }

    @Test
    void answer_includesFixedContextInQuestion() {
        stubRetrieval();
        stubModel("回答");

        EvaluationItem item = EvaluationItem.builder()
                .id(UUID.randomUUID())
                .question("复购率怎么算？")
                .context("当前产品为 SaaS 年付版")
                .build();
        service.answer(skill, item, companyId);

        ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);
        verify(promptAssembly).buildChatMessages(anyString(), questionCaptor.capture(), any(), any());
        String question = questionCaptor.getValue();
        assertTrue(question.contains("当前产品为 SaaS 年付版"));
        assertTrue(question.contains("复购率怎么算？"));
    }

    @Test
    void answer_emptyModelOutput_throws() {
        stubRetrieval();
        when(chatStreamAdapter.chatStream(anyList(), anyMap())).thenReturn(Flux.empty());

        EvaluationItem item = EvaluationItem.builder().id(UUID.randomUUID()).question("问题").build();

        Assertions.assertThrows(IllegalStateException.class, () -> service.answer(skill, item, companyId));
    }

    @Test
    void answer_ragFailure_degradesToEmptyEvidence() throws Exception {
        when(ragPipelineService.retrieveGrainsWithScores(anyString(), any(UUID.class), anyInt(),
                anyString(), any(RagPipelineService.RagContext.class), eq(false)))
                .thenThrow(new IllegalStateException("pgvector 不可用"));
        when(knowledgeCaseRetriever.hasAnyActive(companyId)).thenReturn(false);
        stubModel("降级后的回答");

        EvaluationItem item = EvaluationItem.builder().id(UUID.randomUUID()).question("问题").build();
        EvaluationAnswerService.AnswerResult result = service.answer(skill, item, companyId);

        assertEquals("降级后的回答", result.answer());
        JsonNode evidence = objectMapper.readTree(result.evidenceJson());
        assertEquals(4, evidence.path("fallbackLevel").asInt());
        assertEquals(0, evidence.path("grains").size());
    }

    private void stubRetrieval() {
        ExperienceGrain grain = ExperienceGrain.builder()
                .id(UUID.randomUUID())
                .sceneTag("异议处理")
                .build();
        when(ragPipelineService.retrieveGrainsWithScores(
                anyString(), eq(spaceId), anyInt(), anyString(), any(), eq(false)))
                .thenReturn(new RagPipelineService.GrainResult(
                        List.of(grain),
                        Map.of(grain.getId(), "high"),
                        Map.of(grain.getId(), 0.83)));
        when(knowledgeCaseRetriever.hasAnyActive(companyId)).thenReturn(true);
        when(knowledgeCaseRetriever.retrieve(anyString(), eq(companyId), anyInt(), anyDouble(), anyDouble()))
                .thenReturn(new KnowledgeCaseRetriever.KnowledgeCaseHits(
                        List.of(new KnowledgeCaseRetriever.KnowledgeHit(
                                UUID.randomUUID(), "价格手册", "内容", "price", "出处", 0.71)),
                        List.of(new KnowledgeCaseRetriever.CaseHit(
                                UUID.randomUUID(), "某客户续费案例", "复购", "复购", "win",
                                "{\"industry\":\"制造业\"}", "观望 → 认可", "报价 → 试用 → 签约",
                                "价格异议", "先算 ROI", "话术", "可复用", "无", "出处", 0.62))));
    }

    private void stubModel(String content) {
        when(promptAssembly.buildChatMessages(anyString(), anyString(), any(), any()))
                .thenReturn(List.of(Map.of("role", "user", "content", content)));
        when(chatStreamAdapter.chatStream(anyList(), anyMap()))
                .thenReturn(Flux.just(
                        Map.of("type", "content", "content", content.substring(0, content.length() - 1)),
                        Map.of("type", "content", "content", content.substring(content.length() - 1))));
    }
}
