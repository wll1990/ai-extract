package com.aiextract.service;

import com.aiextract.config.PromptLoader;
import com.aiextract.model.ReviewMessage;
import com.aiextract.model.ReviewReport;
import com.aiextract.model.ReviewSegment;
import com.aiextract.model.ReviewSession;
import com.aiextract.repository.ReviewMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 复盘报告与追问的 Prompt 组装。
 */
@Component
@RequiredArgsConstructor
public class SalesReviewPromptBuilder {

    private final PromptLoader promptLoader;
    private final ReviewMessageRepository messageRepository;

    public record Prompt(String system, String user) {
    }

    public Prompt buildReport(ReviewSession session, List<ReviewSegment> segments,
                              List<GrainRetriever.GrainResult> grains) {
        return buildReport(session, segments, grains, null);
    }

    public Prompt buildReport(ReviewSession session, List<ReviewSegment> segments,
                              List<GrainRetriever.GrainResult> grains, String coachNotes) {
        String system = promptLoader.load("sales_review_report_system.md");
        StringBuilder user = new StringBuilder();
        user.append("【教练画像】")
                .append(nullToEmpty(session.getCoachName())).append(" / ")
                .append(nullToEmpty(session.getCoachTitle())).append('\n');
        user.append("【客户/主题】")
                .append(nullToEmpty(session.getCustomerName())).append(" / ")
                .append(nullToEmpty(session.getTopic())).append('\n');
        user.append("【对话段落】（格式：ID | 说话人 | 原文）\n");
        for (ReviewSegment s : segments) {
            user.append(s.getId()).append(" | ").append(s.getSpeaker())
                    .append(" | ").append(s.getText()).append('\n');
        }
        user.append("【销冠经验颗粒】（仅供参考，不要直接当客户事实）\n");
        if (grains.isEmpty()) {
            user.append("- 无\n");
        } else {
            for (GrainRetriever.GrainResult g : grains) {
                user.append("- ").append(nullToEmpty(g.grain().getSceneTag())).append(": ")
                        .append(truncate(g.grain().getExpertThought(), 400));
                if (g.grain().getStandardScript() != null) {
                    user.append(" / 话术：").append(truncate(g.grain().getStandardScript(), 200));
                }
                user.append('\n');
            }
        }
        if (coachNotes != null && !coachNotes.isBlank()) {
            user.append("【真人教练补充】（优先级高于 AI 推断，但客户事实仍以原文为准）\n")
                    .append(coachNotes).append('\n');
        }
        user.append("【输出 JSON Schema】\n").append(REPORT_SCHEMA);
        return new Prompt(system, user.toString());
    }

    public String buildChatSystem(ReviewSession session, ReviewReport report) {
        return buildChatSystem(session, report, null);
    }

    public String buildChatSystem(ReviewSession session, ReviewReport report, String coachNotes) {
        String base = promptLoader.load("sales_review_chat_system.md");
        StringBuilder sb = new StringBuilder(base).append("\n\n【本次复盘】\n");
        sb.append("客户：").append(nullToEmpty(session.getCustomerName())).append('\n');
        sb.append("主题：").append(nullToEmpty(session.getTopic())).append('\n');
        if (report != null && report.getSummary() != null) {
            sb.append("报告摘要：").append(report.getSummary()).append('\n');
        }
        if (report != null && report.getPayload() != null && !report.getPayload().isBlank()) {
            // 五区块全文让追问能引用具体要点，而不只是摘要
            sb.append("报告全文（JSON）：\n").append(truncate(report.getPayload(), 8000)).append('\n');
        }
        if (coachNotes != null && !coachNotes.isBlank()) {
            sb.append("教练补充：\n").append(coachNotes).append('\n');
        }
        sb.append("对话原文：\n");
        // 复盘创建时已限制 30000 字，这里不再二次截断，避免后半段无法追问
        sb.append(session.getTranscript() == null ? "" : session.getTranscript());
        return sb.toString();
    }

    public List<Map<String, String>> buildChatMessages(String system, UUID sessionId, String content) {
        List<ReviewMessage> history = new ArrayList<>(
                messageRepository.findTop10BySessionIdOrderByCreatedAtDesc(sessionId));
        Collections.reverse(history);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        for (ReviewMessage m : history) {
            messages.add(Map.of("role", m.getRole(), "content", m.getContent()));
        }
        messages.add(Map.of("role", "user", "content", content));
        return messages;
    }

    private static final String REPORT_SCHEMA = """
            {
              "summary": "一句话结论，<=200字",
              "summaryEvidence": [{"segmentId": "uuid", "quote": "支撑这句结论的原文"}],
              "strengths": [{"point": "...", "evidence": [{"segmentId": "uuid", "quote": "原文"}], "confidence": "high|medium|low", "suggestion": "..."}],
              "weaknesses": [{"point": "...", "evidence": [{"segmentId": "uuid", "quote": "原文"}], "confidence": "high|medium|low", "suggestion": "..."}],
              "risks": [{"point": "...", "evidence": [{"segmentId": "uuid", "quote": "原文"}], "confidence": "high|medium|low", "suggestion": "..."}],
              "customerSignals": [{"field": "预算|决策人|时间线|需求|异议", "value": "客户原话能支撑的值，否则 null", "sourceType": "customer_words|sales_paraphrase|ai_inference|unknown", "evidence": [{"segmentId": "uuid", "quote": "原文"}], "confidence": "high|medium|low"}],
              "primaryAction": {"title": "下次沟通先做的唯一一件事", "channel": "电话|微信|邮件|拜访", "dueInDays": 1, "content": "可以直接照着说的动作/话术", "evidence": [{"segmentId": "uuid"}]},
              "infoGaps": [{"field": "预算|决策人|时间线|需求|异议", "why": "为什么这条信息影响推进", "question": "下次可以直接问客户的一句话"}],
              "nextSteps": [{"title": "...", "channel": "电话|微信|邮件|拜访", "dueInDays": 1, "content": "...", "evidence": [{"segmentId": "uuid"}]}],
              "leadDraft": {"name": "客户/公司名", "contactName": "...", "contactPhone": "...", "needSummary": "..."}
            }
            """;

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
