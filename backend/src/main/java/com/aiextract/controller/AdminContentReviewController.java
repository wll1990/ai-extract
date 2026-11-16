package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.service.ContentReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * AI 草稿审核台：知识 / 案例 / 考题统一入口。
 *
 * <p>只处理带生成任务的草稿；通过/丢弃复用各模块原有审批与质量门禁。</p>
 */
@RestController
@RequestMapping("/admin/content/review")
@RequiredArgsConstructor
public class AdminContentReviewController {

    private final ContentReviewService reviewService;

    @GetMapping
    public ApiResponse<ContentReviewService.DraftList> list(
            @RequestParam(defaultValue = "knowledge") String kind,
            @RequestParam(required = false) UUID datasetId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(reviewService.list(kind, datasetId, page, size));
    }

    @PostMapping("/knowledge/{id}/approve")
    public ApiResponse<Map<String, Object>> approveKnowledge(
            @PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        reviewService.approveKnowledge(id, parseExpireAt(body));
        return ApiResponse.success(Map.of("id", id.toString(), "status", "approved"));
    }

    @PostMapping("/case/{id}/approve")
    public ApiResponse<Map<String, Object>> approveCase(
            @PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        reviewService.approveCase(id, parseExpireAt(body));
        return ApiResponse.success(Map.of("id", id.toString(), "status", "approved"));
    }

    @PostMapping("/eval-item/{id}/approve")
    public ApiResponse<Map<String, Object>> approveEvalItem(@PathVariable UUID id) {
        reviewService.approveEvalItem(id);
        return ApiResponse.success(Map.of("id", id.toString(), "status", "active"));
    }

    @PostMapping("/{kind}/{id}/reject")
    public ApiResponse<Map<String, Object>> reject(
            @PathVariable String kind, @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        Object reason = body == null ? null : body.get("reason");
        reviewService.reject(kind, id, reason == null ? null : String.valueOf(reason));
        return ApiResponse.success(Map.of("id", id.toString(), "status", "rejected"));
    }

    private LocalDateTime parseExpireAt(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("expireAt");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(String.valueOf(raw));
        } catch (Exception e) {
            throw new BusinessException(400, "expireAt 格式非法，需 ISO-8601（如 2027-01-01T00:00:00）");
        }
    }
}
