package com.aiextract.dto;

import com.aiextract.model.ReviewReport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 复盘报告 → 视图的唯一转换入口。
 * 两个业务入口（复盘详情、教练补充）必须使用同一套坏 payload 处理策略。
 */
@Slf4j
public final class ReviewReportViewMapper {

    private ReviewReportViewMapper() {}

    /** 列表场景只返回元数据，payload 按需单独拉取 */
    public static ReviewReportView toMetaView(ReviewReport r) {
        return new ReviewReportView(r.getId(), r.getVersion(), r.getStatus(), r.getSummary(),
                null, r.getErrorMessage(), r.getModelName(),
                r.getCreatedAt(), r.getStartedAt(), r.getGeneratedAt());
    }

    public static ReviewReportView toView(ReviewReport r, ObjectMapper objectMapper) {
        JsonNode payload = null;
        String error = r.getErrorMessage();
        if (r.getPayload() != null && !r.getPayload().isBlank()) {
            try {
                payload = objectMapper.readTree(r.getPayload());
            } catch (Exception e) {
                log.warn("复盘报告 payload 解析失败 reportId={}", r.getId(), e);
                if (error == null || error.isBlank()) {
                    error = "报告内容损坏，请重新生成";
                }
            }
        }
        return new ReviewReportView(r.getId(), r.getVersion(), r.getStatus(), r.getSummary(),
                payload, error, r.getModelName(),
                r.getCreatedAt(), r.getStartedAt(), r.getGeneratedAt());
    }
}
