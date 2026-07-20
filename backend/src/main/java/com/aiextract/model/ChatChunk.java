package com.aiextract.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 流式聊天事件 — 协议无关的 SSE 传输单元
 *
 * <p>Service 层输出此类事件，Controller / IM Adapter 决定如何序列化和传输。
 * 发往 H5/小程序走 SseEmitter，发往飞书 bot 走消息回写 API。</p>
 *
 * <p>常用工厂方法：{@link #content(String)} / {@link #done()} / {@link #error(String)} / {@link #meta(String, Object)}</p>
 *
 * @author AI Extract Team
 * @since 2026-07-14
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatChunk {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String KEY_TYPE = "type";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_CONVERSATION_ID = "conversationId";
    private static final String KEY_REPORT_ID = "reportId";
    private static final String KEY_REPORT_TITLE = "reportTitle";
    private static final String KEY_GRAIN_IDS = "grainIds";
    private static final String KEY_GRAIN_TAGS = "grainTags";
    private static final String KEY_GRAIN_COUNT = "grainCount";
    private static final String KEY_AVG_SCORE = "avgScore";
    private static final String KEY_AVG_SIMILARITY = "avgSimilarity";
    private static final String KEY_SOURCE_NAMES = "sourceNames";
    private static final String KEY_ACTION = "action";
    private static final String KEY_DATA = "data";
    private static final String KEY_SKILL_ID = "skillId";

    /** 事件类型：content | done | error | meta | source | warning | customer | avatar */
    private String type;

    /** 文本内容（type=content 时有效） */
    private String content;

    /** 结构化数据（type=customer/avatar 等自定义事件时有效） */
    private Map<String, Object> data;

    // ---- 以下按 type 按需设值，null 时不序列化 ----

    /** 错误信息（type=error 时有效） */
    private String message;

    /** 所属对话 ID（type=meta 时有效） */
    private String conversationId;

    /** 来源报告 ID（type=source 时有效） */
    private String reportId;
    private String reportTitle;
    private String grainIds;
    private String grainTags;
    private Integer grainCount;
    private String avgScore;
    private String avgSimilarity;

    /** 来源名称列表（type=source 时有效）：素材文件名或访谈主题，逗号分隔 */
    private String sourceNames;

    /** 警告信息（type=warning 时有效） */
    private String action;
    private String skillId;

    // ---- 工厂方法 ----

    public static ChatChunk content(String text) {
        ChatChunk c = new ChatChunk();
        c.type = "content";
        c.content = text;
        return c;
    }

    public static ChatChunk done() {
        ChatChunk c = new ChatChunk();
        c.type = "done";
        return c;
    }

    public static ChatChunk error(String msg) {
        ChatChunk c = new ChatChunk();
        c.type = "error";
        c.message = msg;
        return c;
    }

    public static ChatChunk meta(String conversationId) {
        ChatChunk c = new ChatChunk();
        c.type = "meta";
        c.conversationId = conversationId;
        return c;
    }

    public static ChatChunk warning(String message, String action, String skillId) {
        ChatChunk c = new ChatChunk();
        c.type = "warning";
        c.message = message;
        c.action = action;
        c.skillId = skillId;
        return c;
    }

    public static ChatChunk source(String reportId, String reportTitle, String grainIds,
                                     String grainTags, int grainCount, String avgScore,
                                     String avgSimilarity, String sourceNames) {
        ChatChunk c = new ChatChunk();
        c.type = "source";
        c.reportId = reportId;
        c.reportTitle = reportTitle;
        c.grainIds = grainIds;
        c.grainTags = grainTags;
        c.grainCount = grainCount;
        c.avgScore = avgScore;
        c.avgSimilarity = avgSimilarity;
        c.sourceNames = sourceNames;
        return c;
    }

    /** 自定义事件（type=customer/avatar 等） */
    public static ChatChunk event(String type, Map<String, Object> data) {
        ChatChunk c = new ChatChunk();
        c.type = type;
        c.data = data;
        return c;
    }

    /** 从 ChatStreamAdapter 输出的 Map 构造（兼容旧格式） */
    @SuppressWarnings("unchecked")
    public static ChatChunk fromEventMap(Map<String, Object> event) {
        ChatChunk c = new ChatChunk();
        c.type = event.get(KEY_TYPE) != null ? event.get(KEY_TYPE).toString() : null;
        c.content = event.get(KEY_CONTENT) != null ? event.get(KEY_CONTENT).toString() : null;
        c.message = event.get(KEY_MESSAGE) != null ? event.get(KEY_MESSAGE).toString() : null;
        c.conversationId = event.get(KEY_CONVERSATION_ID) != null ? event.get(KEY_CONVERSATION_ID).toString() : null;
        c.reportId = event.get(KEY_REPORT_ID) != null ? event.get(KEY_REPORT_ID).toString() : null;
        c.reportTitle = event.get(KEY_REPORT_TITLE) != null ? event.get(KEY_REPORT_TITLE).toString() : null;
        c.grainIds = event.get(KEY_GRAIN_IDS) != null ? event.get(KEY_GRAIN_IDS).toString() : null;
        c.grainTags = event.get(KEY_GRAIN_TAGS) != null ? event.get(KEY_GRAIN_TAGS).toString() : null;
        if (event.get(KEY_GRAIN_COUNT) != null) {
            c.grainCount = event.get(KEY_GRAIN_COUNT) instanceof Number
                ? ((Number) event.get(KEY_GRAIN_COUNT)).intValue()
                : Integer.parseInt(event.get(KEY_GRAIN_COUNT).toString());
        }
        c.avgScore = event.get(KEY_AVG_SCORE) != null ? event.get(KEY_AVG_SCORE).toString() : null;
        c.avgSimilarity = event.get(KEY_AVG_SIMILARITY) != null ? event.get(KEY_AVG_SIMILARITY).toString() : null;
        c.sourceNames = event.get(KEY_SOURCE_NAMES) != null ? event.get(KEY_SOURCE_NAMES).toString() : null;
        c.action = event.get(KEY_ACTION) != null ? event.get(KEY_ACTION).toString() : null;
        c.skillId = event.get(KEY_SKILL_ID) != null ? event.get(KEY_SKILL_ID).toString() : null;
        return c;
    }

    /** 序列化为 SSE 事件的 data 字段 JSON */
    public String toSseJson() {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(KEY_TYPE, type);
            if (content != null) { m.put(KEY_CONTENT, content); }
            if (data != null) { m.put(KEY_DATA, data); }
            if (message != null) { m.put(KEY_MESSAGE, message); }
            if (conversationId != null) { m.put(KEY_CONVERSATION_ID, conversationId); }
            if (reportId != null) { m.put(KEY_REPORT_ID, reportId); }
            if (reportTitle != null) { m.put(KEY_REPORT_TITLE, reportTitle); }
            if (grainIds != null) { m.put(KEY_GRAIN_IDS, grainIds); }
            if (grainTags != null) { m.put(KEY_GRAIN_TAGS, grainTags); }
            if (grainCount != null) { m.put(KEY_GRAIN_COUNT, grainCount); }
            if (avgScore != null) { m.put(KEY_AVG_SCORE, avgScore); }
            if (avgSimilarity != null) { m.put(KEY_AVG_SIMILARITY, avgSimilarity); }
            if (sourceNames != null) { m.put(KEY_SOURCE_NAMES, sourceNames); }
            if (action != null) { m.put(KEY_ACTION, action); }
            if (skillId != null) { m.put(KEY_SKILL_ID, skillId); }
            return MAPPER.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"error\",\"message\":\"序列化失败\"}";
        }
    }

    // ---- getters (Jackson 需要) ----

    public String getType() { return type; }
    public String getContent() { return content; }
    public Map<String, Object> getData() { return data; }
    public String getMessage() { return message; }
    public String getConversationId() { return conversationId; }
    public String getReportId() { return reportId; }
    public String getReportTitle() { return reportTitle; }
    public String getGrainIds() { return grainIds; }
    public String getGrainTags() { return grainTags; }
    public Integer getGrainCount() { return grainCount; }
    public String getAvgScore() { return avgScore; }
    public String getAvgSimilarity() { return avgSimilarity; }
    public String getAction() { return action; }
    public String getSkillId() { return skillId; }
}
