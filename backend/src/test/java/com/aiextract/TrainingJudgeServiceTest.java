package com.aiextract;

import com.aiextract.config.PromptLoader;
import com.aiextract.model.TrainingTask;
import com.aiextract.service.ChatStreamAdapter;
import com.aiextract.service.TrainingJudgeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * TrainingJudgeService 单元测试 —— 解析、归一化、红线/未判定识别、失败不造假。
 */
@ExtendWith(MockitoExtension.class)
class TrainingJudgeServiceTest {

    @Mock private ChatStreamAdapter chatStreamAdapter;
    @Mock private PromptLoader promptLoader;

    private TrainingJudgeService service;

    @BeforeEach
    void setUp() {
        service = new TrainingJudgeService(chatStreamAdapter, promptLoader, new ObjectMapper());
        when(promptLoader.format(eq("training_judge.md"),
                org.mockito.ArgumentMatchers.<Map<String, String>>any())).thenReturn("prompt");
    }

    @Test
    void judgeExamItem_parsesAndClampsValues() {
        when(chatStreamAdapter.chat(anyString(), anyString())).thenReturn(
                "{\"total_score\":150,\"confidence\":1.5,\"hit_points\":[\"要点A\"],"
                        + "\"missed_points\":[\"要点B\"],\"forbidden_hits\":[],"
                        + "\"verdict_suggestion\":\"pass\",\"reason\":\"覆盖要点A\"}");

        TrainingJudgeService.Judgement judged = service.judgeExamItem(
                task(), new ObjectMapper().createObjectNode(), null, "回答");

        assertEquals(100, judged.totalScore(), "分数必须夹到 0-100");
        assertEquals(1.0, judged.confidence(), "置信度必须夹到 0-1");
        assertEquals(1, judged.hitPoints().size());
        assertEquals(1, judged.missedPoints().size());
        assertFalse(judged.forbiddenHit());
        assertFalse(judged.undetermined());
    }

    @Test
    void judgeExamItem_forbiddenHitDetected() {
        when(chatStreamAdapter.chat(anyString(), anyString())).thenReturn(
                "{\"total_score\":90,\"confidence\":0.9,\"hit_points\":[],\"missed_points\":[],"
                        + "\"forbidden_hits\":[\"承诺收益\"],\"verdict_suggestion\":\"fail\","
                        + "\"reason\":\"触红线\"}");

        TrainingJudgeService.Judgement judged = service.judgeExamItem(
                task(), new ObjectMapper().createObjectNode(), null, "回答");

        assertTrue(judged.forbiddenHit());
        assertEquals(1, judged.forbiddenHits().size());
        assertEquals("fail", judged.suggestion());
    }

    @Test
    void judgePractice_undeterminedDetected() {
        when(chatStreamAdapter.chat(anyString(), anyString())).thenReturn(
                "{\"total_score\":50,\"confidence\":0.3,\"hit_points\":[],\"missed_points\":[],"
                        + "\"forbidden_hits\":[],\"verdict_suggestion\":\"undetermined\","
                        + "\"reason\":\"对话过短\"}");

        TrainingJudgeService.Judgement judged = service.judgePractice(task(), "学员：你好");

        assertTrue(judged.undetermined());
        assertEquals(0.3, judged.confidence());
    }

    @Test
    void judge_stripsMarkdownCodeFence() {
        when(chatStreamAdapter.chat(anyString(), anyString())).thenReturn(
                "```json\n{\"total_score\":75,\"confidence\":0.8,\"verdict_suggestion\":\"pass\"}\n```");

        TrainingJudgeService.Judgement judged = service.judgePractice(task(), "学员：你好");

        assertEquals(75, judged.totalScore());
        assertFalse(judged.undetermined());
    }

    @Test
    void judge_parseFailure_throwsInsteadOfFakeScore() {
        when(chatStreamAdapter.chat(anyString(), anyString())).thenReturn("我觉得他答得不错");

        assertThrows(IllegalStateException.class,
                () -> service.judgePractice(task(), "学员：你好"));
    }

    @Test
    void judge_emptyResponse_throws() {
        when(chatStreamAdapter.chat(anyString(), anyString())).thenReturn("   ");

        assertThrows(IllegalStateException.class,
                () -> service.judgePractice(task(), "学员：你好"));
    }

    @Test
    void judge_missingRequiredField_throwsInsteadOfZeroScore() {
        when(chatStreamAdapter.chat(anyString(), anyString()))
                .thenReturn("{\"total_score\":80}");

        assertThrows(IllegalStateException.class,
                () -> service.judgePractice(task(), "学员：你好"));
    }

    @Test
    void judge_illegalSuggestion_throws() {
        when(chatStreamAdapter.chat(anyString(), anyString())).thenReturn(
                "{\"total_score\":80,\"confidence\":0.9,\"verdict_suggestion\":\"approved\"}");

        assertThrows(IllegalStateException.class,
                () -> service.judgePractice(task(), "学员：你好"));
    }

    @Test
    void judgeExamItem_uncheckedForbiddenPointIsReported() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode key = mapper.readTree(
                "{\"expectedPoints\":[\"要点A\"],\"forbiddenPoints\":[\"承诺收益\",\"返现\"]}");
        when(chatStreamAdapter.chat(anyString(), anyString())).thenReturn(
                "{\"total_score\":80,\"confidence\":0.9,\"hit_points\":[\"要点A\"],"
                        + "\"verdict_suggestion\":\"pass\",\"forbidden_hits\":[],"
                        + "\"forbidden_checks\":[{\"point\":\"承诺收益\",\"hit\":false}],\"reason\":\"r\"}");

        TrainingJudgeService.Judgement judged = service.judgeExamItem(
                task(), mapper.createObjectNode(), key, "回答");

        assertEquals(1, judged.uncheckedForbidden().size(), "漏检的红线必须暴露");
        assertEquals("返现", judged.uncheckedForbidden().get(0));
        assertFalse(judged.forbiddenHit());
    }

    @Test
    void judgeExamItem_forbiddenCheckHitCountsAsForbidden() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode key = mapper.readTree(
                "{\"expectedPoints\":[\"要点A\"],\"forbiddenPoints\":[\"承诺收益\"]}");
        when(chatStreamAdapter.chat(anyString(), anyString())).thenReturn(
                "{\"total_score\":90,\"confidence\":0.9,\"hit_points\":[],"
                        + "\"verdict_suggestion\":\"fail\",\"forbidden_hits\":[],"
                        + "\"forbidden_checks\":[{\"point\":\"承诺收益\",\"hit\":true,"
                        + "\"quote\":\"保证年化 20%\"}],\"reason\":\"触红线\"}");

        TrainingJudgeService.Judgement judged = service.judgeExamItem(
                task(), mapper.createObjectNode(), key, "保证年化 20%");

        assertTrue(judged.forbiddenHit());
        assertEquals(1, judged.forbiddenHits().size());
        assertTrue(judged.uncheckedForbidden().isEmpty());
    }

    @Test
    void judgeExamItem_citationFailureReportedWhenRequired() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode key = mapper.readTree("{\"expectedPoints\":[\"要点A\"],\"forbiddenPoints\":[]}");
        JsonNode question = mapper.readTree(
                "{\"citationRequirement\":{\"type\":\"source\"}}");
        when(chatStreamAdapter.chat(anyString(), anyString())).thenReturn(
                "{\"total_score\":80,\"confidence\":0.9,\"hit_points\":[\"要点A\"],"
                        + "\"verdict_suggestion\":\"pass\",\"forbidden_hits\":[],"
                        + "\"citation_ok\":false,\"reason\":\"未引用\"}");

        TrainingJudgeService.Judgement judged = service.judgeExamItem(
                task(), question, key, "回答");

        assertTrue(judged.citationRequired());
        assertFalse(judged.citationOk());
    }

    private TrainingTask task() {
        return TrainingTask.builder()
                .id(UUID.randomUUID()).planId(UUID.randomUUID()).version(1)
                .type(TrainingTask.TYPE_PRACTICE).title("陪练").phaseNo(1).orderNo(1)
                .build();
    }
}
