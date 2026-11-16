package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.dto.CoachNoteCreateRequest;
import com.aiextract.dto.CoachNoteView;
import com.aiextract.dto.CoachRequestCreateRequest;
import com.aiextract.dto.CoachRequestView;
import com.aiextract.dto.CoachSupplementView;
import com.aiextract.dto.CoachWorkspaceView;
import com.aiextract.service.SalesCoachSupplementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SalesCoachController {

    private final SalesCoachSupplementService service;

    @PostMapping("/sales/reviews/{id}/coach-request")
    public ApiResponse<CoachSupplementView> request(
            @PathVariable UUID id, @Valid @RequestBody CoachRequestCreateRequest req) {
        return ApiResponse.success(service.request(id, req));
    }

    @PostMapping("/sales/reviews/{id}/coach-request/cancel")
    public ApiResponse<Void> cancel(@PathVariable UUID id) {
        service.cancel(id);
        return ApiResponse.success(null);
    }

    @GetMapping("/sales/reviews/{id}/coach-supplement")
    public ApiResponse<CoachSupplementView> supplement(@PathVariable UUID id) {
        return ApiResponse.success(service.supplementView(id));
    }

    @GetMapping("/sales/coach/requests")
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "true") boolean mine) {
        return ApiResponse.success(PageResponse.of(service.list(page, size, status, mine), page, size));
    }

    /** SA-08 可转交的同事列表 */
    @GetMapping("/sales/coach/coaches")
    public ApiResponse<java.util.List<Map<String, Object>>> coaches() {
        return ApiResponse.success(service.listCoaches());
    }

    /** SA-08 标记已读（单条/批量共用） */
    @PostMapping("/sales/coach/requests/mark-read")
    public ApiResponse<Map<String, Object>> markRead(@RequestBody Map<String, Object> body) {
        java.util.List<UUID> ids = parseIds(body);
        int updated = service.markReadBatch(ids);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("updated", updated);
        return ApiResponse.success(result);
    }

    /** SA-08 回复单条 */
    @PostMapping("/sales/coach/requests/{id}/reply")
    public ApiResponse<CoachRequestView> reply(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        String content = body.get("content") instanceof String c ? c : "";
        return ApiResponse.success(service.reply(id, content));
    }

    /** SA-08 批量回复（同一内容） */
    @PostMapping("/sales/coach/requests/reply-batch")
    public ApiResponse<Map<String, Object>> replyBatch(@RequestBody Map<String, Object> body) {
        String content = body.get("content") instanceof String c ? c : "";
        return ApiResponse.success(service.replyBatch(parseIds(body), content));
    }

    /** SA-08 转交 */
    @PostMapping("/sales/coach/requests/{id}/transfer")
    public ApiResponse<CoachRequestView> transfer(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        UUID coachId;
        try {
            coachId = UUID.fromString(String.valueOf(body.get("coachId")));
        } catch (IllegalArgumentException e) {
            throw new com.aiextract.exception.BusinessException(400, "请选择转交对象");
        }
        return ApiResponse.success(service.transfer(id, coachId));
    }

    private java.util.List<UUID> parseIds(Map<String, Object> body) {
        if (body == null || !(body.get("ids") instanceof java.util.List<?> raw) || raw.isEmpty()) {
            throw new com.aiextract.exception.BusinessException(400, "请选择请求");
        }
        if (raw.size() > 100) {
            throw new com.aiextract.exception.BusinessException(400, "单次最多处理 100 条");
        }
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        for (Object o : raw) {
            try {
                ids.add(UUID.fromString(String.valueOf(o)));
            } catch (IllegalArgumentException e) {
                throw new com.aiextract.exception.BusinessException(400, "非法请求 ID: " + o);
            }
        }
        return ids;
    }

    @GetMapping("/sales/coach/requests/{sessionId}")
    public ApiResponse<CoachWorkspaceView> workspace(@PathVariable UUID sessionId) {
        return ApiResponse.success(service.workspace(sessionId));
    }

    @PostMapping("/sales/coach/requests/{sessionId}/notes")
    public ApiResponse<CoachNoteView> addNote(
            @PathVariable UUID sessionId, @Valid @RequestBody CoachNoteCreateRequest req) {
        return ApiResponse.success(service.addNote(sessionId, req.content()));
    }
}
