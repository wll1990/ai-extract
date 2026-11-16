package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.PageResponse;
import com.aiextract.config.SseAdapter;
import com.aiextract.dto.LeadConvertRequest;
import com.aiextract.dto.LeadConvertResult;
import com.aiextract.dto.ReviewCreateRequest;
import com.aiextract.dto.ReviewDetailView;
import com.aiextract.dto.ReviewMessageRequest;
import com.aiextract.dto.ReviewMessageView;
import com.aiextract.dto.ReviewReportView;
import com.aiextract.dto.ShareTranscriptRequest;
import com.aiextract.dto.TaskDraftListRequest;
import com.aiextract.service.SalesLeadService;
import com.aiextract.service.SalesReviewChatService;
import com.aiextract.service.SalesReviewService;
import com.aiextract.service.SalesTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/sales/reviews")
@RequiredArgsConstructor
public class SalesReviewController {

    private final SalesReviewService reviewService;
    private final SalesReviewChatService chatService;
    private final SalesLeadService leadService;
    private final SalesTaskService taskService;

    @PostMapping
    public ApiResponse<ReviewDetailView> create(@Valid @RequestBody ReviewCreateRequest req) {
        return ApiResponse.success(reviewService.create(req));
    }

    /** 复盘沉淀为知识/案例草稿（pending），跳转详情页确认 */
    @PostMapping("/{id}/precipitate")
    public ApiResponse<Map<String, Object>> precipitate(@PathVariable UUID id,
                                                        @RequestBody Map<String, String> body) {
        String target = body.getOrDefault("target", "case");
        return ApiResponse.success(reviewService.precipitate(id, target));
    }

    /** 上传复盘文件（txt/docx/pdf）→ 解析为文本，前端预览/编辑后再提交创建 */
    @PostMapping(value = "/parse-file", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, Object>> parseFile(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        String text = reviewService.parseTranscriptFile(file);
        return ApiResponse.success(Map.of("text", text, "length", text.length()));
    }

    @GetMapping("/coaches")
    public ApiResponse<Map<String, Object>> coaches(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(PageResponse.of(
                reviewService.searchCoaches(keyword, page, size), page, size));
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "mine") String scope) {
        return ApiResponse.success(PageResponse.of(
                reviewService.list(page, size, status, scope), page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ReviewDetailView> detail(@PathVariable UUID id) {
        return ApiResponse.success(reviewService.detail(id));
    }

    /** 同一客户的上一次复盘（无关联客户/无历史时返回 null） */
    @GetMapping("/{id}/previous")
    public ApiResponse<com.aiextract.dto.PreviousReviewView> previous(@PathVariable UUID id) {
        return ApiResponse.success(reviewService.previous(id));
    }

    @PostMapping("/{id}/report")
    public ApiResponse<Map<String, UUID>> requestReport(@PathVariable UUID id) {
        return ApiResponse.success(Map.of("reportId", reviewService.requestReport(id)));
    }

    @GetMapping("/{id}/report")
    public ApiResponse<ReviewReportView> latestReport(@PathVariable UUID id) {
        return ApiResponse.success(reviewService.latestReport(id));
    }

    @GetMapping("/{id}/reports")
    public ApiResponse<List<ReviewReportView>> reports(@PathVariable UUID id) {
        return ApiResponse.success(reviewService.reports(id));
    }

    @GetMapping("/{id}/reports/{reportId}")
    public ApiResponse<ReviewReportView> report(@PathVariable UUID id, @PathVariable UUID reportId) {
        return ApiResponse.success(reviewService.report(id, reportId));
    }

    @PatchMapping("/{id}/share")
    public ApiResponse<Map<String, Boolean>> updateShare(
            @PathVariable UUID id, @Valid @RequestBody ShareTranscriptRequest req) {
        return ApiResponse.success(Map.of("shareTranscriptWithCoach",
                reviewService.updateShare(id, req.shareTranscriptWithCoach())));
    }

    @GetMapping("/{id}/messages")
    public ApiResponse<List<ReviewMessageView>> messages(@PathVariable UUID id) {
        return ApiResponse.success(chatService.history(id));
    }

    @PostMapping("/{id}/messages")
    public SseEmitter sendMessage(@PathVariable UUID id,
                                  @Valid @RequestBody ReviewMessageRequest req) {
        return SseAdapter.fromFlux(chatService.stream(id, req.content()));
    }

    @PostMapping("/{id}/leads")
    public ApiResponse<LeadConvertResult> convertLead(
            @PathVariable UUID id, @Valid @RequestBody LeadConvertRequest req) {
        return ApiResponse.success(leadService.convertFromReview(id, req));
    }

    @PostMapping("/{id}/tasks")
    public ApiResponse<Map<String, Object>> createTasks(
            @PathVariable UUID id, @Valid @RequestBody TaskDraftListRequest req) {
        return ApiResponse.success(taskService.createFromReview(id, req.drafts()));
    }
}
