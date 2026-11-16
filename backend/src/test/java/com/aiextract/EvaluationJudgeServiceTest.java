package com.aiextract;

import com.aiextract.config.PromptLoader;
import com.aiextract.model.EvaluationItem;
import com.aiextract.model.EvaluationRunItem;
import com.aiextract.service.ChatStreamAdapter;
import com.aiextract.service.EvaluationJudgeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * EvaluationJudgeService 单元测试 —— 验证评分解析与「结论由服务端确定性判定」的规则。
 */
@ExtendWith(MockitoExtension.class)
class EvaluationJudgeServiceTest {

    @Mock
    private ChatStreamAdapter chatStreamAdapter;
    @Mock
    private PromptLoader promptLoader;

    private EvaluationJudgeService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final EvaluationItem item = EvaluationItem.builder()
            .id(UUID.randomUUID())
            .question("复购率怎么算？")
            .expectedPoints("[\"按月复购客户数/月活跃客户数\"]")
            .forbiddenPoints("[\"把复购率说成留存率\"]")
            .build();
    private final EvaluationRunItem runItem = EvaluationRunItem.builder().id(UUID.randomUUID()).build();

    @BeforeEach
    void setUp() {
        service = new EvaluationJudgeService(chatStreamAdapter, promptLoader, objectMapper);
        ReflectionTestUtils.setField(service, "passThreshold", 60);
        ReflectionTestUtils.setField(service, "reviewConfThreshold", 0.60);
        ReflectionTestUtils.setField(service, "reviewBand", 5);
        when(promptLoader.format(anyString(), anyMap())).thenReturn("judge-prompt");
    }

    @Test
    void judge_scoreAboveThreshold_returnsPassWithoutReview() {
        stubJudge(85, 0.9, "[]", "pass");

        EvaluationJudgeService.JudgeOutcome outcome = service.judge(runItem, item, "答案", "acceptance");

        assertEquals(EvaluationRunItem.VERDICT_PASS, outcome.verdict());
        assertFalse(outcome.needReview());
    }

    @Test
    void judge_forbiddenHitForcesFailAndReview() {
        // 即使模型给高分并建议 pass，命中红线也必须 fail + 必审
        stubJudge(95, 0.95, "[\"把复购率说成留存率\"]", "pass");

        EvaluationJudgeService.JudgeOutcome outcome = service.judge(runItem, item, "答案", "acceptance");

        assertEquals(EvaluationRunItem.VERDICT_FAIL, outcome.verdict());
        assertTrue(outcome.needReview());
    }

    @Test
    void judge_scoreNearThreshold_requiresReviewButKeepsVerdict() {
        stubJudge(62, 0.9, "[]", "pass");

        EvaluationJudgeService.JudgeOutcome outcome = service.judge(runItem, item, "答案", "acceptance");

        assertEquals(EvaluationRunItem.VERDICT_PASS, outcome.verdict());
        assertTrue(outcome.needReview());
    }

    @Test
    void judge_lowConfidence_requiresReview() {
        stubJudge(85, 0.30, "[]", "pass");

        EvaluationJudgeService.JudgeOutcome outcome = service.judge(runItem, item, "答案", "acceptance");

        assertTrue(outcome.needReview());
    }

    @Test
    void judge_modelSuggestsFailButScorePasses_requiresReview() {
        // 模型按要点覆盖判 fail（漏掉核心要点），服务端按分数判 pass —— 口径分歧必须人工定夺
        stubJudge(75, 0.9, "[]", "fail");

        EvaluationJudgeService.JudgeOutcome outcome = service.judge(runItem, item, "答案", "acceptance");

        assertEquals(EvaluationRunItem.VERDICT_PASS, outcome.verdict());
        assertTrue(outcome.needReview());
    }

    @Test
    void judge_undeterminedSuggestion_returnsUndetermined() {
        stubJudge(85, 0.9, "[]", "undetermined");

        EvaluationJudgeService.JudgeOutcome outcome = service.judge(runItem, item, "答案", "acceptance");

        assertEquals(EvaluationRunItem.VERDICT_UNDETERMINED, outcome.verdict());
    }

    @Test
    void judge_scoreBelowThreshold_returnsFail() {
        stubJudge(40, 0.9, "[]", "pass");

        EvaluationJudgeService.JudgeOutcome outcome = service.judge(runItem, item, "答案", "acceptance");

        assertEquals(EvaluationRunItem.VERDICT_FAIL, outcome.verdict());
        assertFalse(outcome.needReview());
    }

    @Test
    void judge_persistsConfidenceAndReviewFlags() throws Exception {
        stubJudge(85, 0.9, "[]", "pass");

        EvaluationJudgeService.JudgeOutcome outcome = service.judge(runItem, item, "答案", "acceptance");
        JsonNode score = objectMapper.readTree(outcome.aiScoreJson());
        JsonNode ruleCheck = objectMapper.readTree(outcome.ruleCheckJson());

        assertEquals(85, score.path("total_score").asInt());
        assertEquals(0.9, score.path("confidence").asDouble(), 1e-9);
        assertTrue(score.has("need_review"));
        assertEquals(60, score.path("pass_threshold").asInt());
        assertTrue(ruleCheck.has("forbidden_hits"));
        assertTrue(ruleCheck.path("citation_ok").asBoolean());
    }

    @Test
    void judge_malformedJson_throwsInsteadOfFakeScore() {
        when(chatStreamAdapter.chat("judge-prompt")).thenReturn("not-a-json");

        assertThrows(IllegalStateException.class, () -> service.judge(runItem, item, "答案", "acceptance"));
    }

    @Test
    void judge_emptyResponse_throws() {
        when(chatStreamAdapter.chat("judge-prompt")).thenReturn("  ");

        assertThrows(IllegalStateException.class, () -> service.judge(runItem, item, "答案", "acceptance"));
    }

    /** 构造裁判模型的标准输出（含 markdown 代码块包裹，验证清理逻辑）。 */
    private void stubJudge(int total, double confidence, String forbiddenHits, String suggestion) {
        String json = "```json\n{\"style_score\":80,\"consistency_score\":80,\"behavior_score\":80,"
                + "\"script_reuse_score\":80,\"total_score\":" + total + ",\"confidence\":" + confidence + ","
                + "\"hit_points\":[\"按月复购客户数/月活跃客户数\"],\"missed_points\":[],"
                + "\"forbidden_hits\":" + forbiddenHits + ",\"citation_ok\":true,"
                + "\"verdict_suggestion\":\"" + suggestion + "\",\"reason\":\"覆盖核心要点\"}\n```";
        when(chatStreamAdapter.chat("judge-prompt")).thenReturn(json);
    }
}
