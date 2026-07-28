package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.dto.ReportDetailResponse;
import com.aiextract.dto.ReportListResponse;
import com.aiextract.dto.UpdateReportRequest;
import com.aiextract.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 报告控制器
 *
 * <p>提供报告列表查询、详情查看、内容编辑和文件下载四个接口。
 * 下载支持Word和PPT两种格式。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final com.aiextract.util.JwtUtil jwtUtil;

    private String getToken() {
        return (String) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getCredentials();
    }

    /**
     * 获取报告列表
     *
     * @param spaceId 空间ID（可选）
     * @param page    页码（默认1）
     * @param size    每页条数（默认20）
     * @return 报告分页列表
     */
    @GetMapping
    public ApiResponse<Page<ReportListResponse>> getReports(
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ReportListResponse> reports = reportService.getReports(spaceId, keyword, page, size);
        return ApiResponse.success(reports);
    }

    /**
     * 获取报告详情
     *
     * @param reportId 报告ID
     * @return 报告完整详情
     */
    @GetMapping("/{reportId}")
    public ApiResponse<ReportDetailResponse> getReport(@PathVariable String reportId) {
        ReportDetailResponse response = reportService.getReport(reportId);
        return ApiResponse.success(response);
    }

    /**
     * 按访谈 sessionId 获取报告 HTML（含就绪检查）。
     * 报告未就绪时返回 202 + 颗粒/场景统计；就绪时返回 200 + HTML。
     */
    @GetMapping("/by-session/{sessionId}/html")
    public org.springframework.http.ResponseEntity<?> getReportHtmlBySession(@PathVariable String sessionId) {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        ReportService.ReportHtmlResult result = reportService.getReportHtmlBySession(UUID.fromString(sessionId), userId);
        if (!result.ready()) {
            return org.springframework.http.ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED)
                    .body(Map.of("ready", false, "grains", result.grains(), "scenes", result.scenes(),
                            "needGrains", result.needGrains(), "needScenes", result.needScenes()));
        }
        return org.springframework.http.ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(result.html());
    }

    /**
     * 编辑报告内容
     *
     * <p>更新报告章节内容，可选择是否重新生成Word/PPT。</p>
     *
     * @param reportId 报告ID
     * @param request  编辑请求（chapters + regenerate标志）
     * @return 更新后的报告详情
     */
    @PutMapping("/{reportId}")
    public ApiResponse<ReportDetailResponse> updateReport(
            @PathVariable String reportId,
            @RequestBody UpdateReportRequest request) {
        ReportDetailResponse response = reportService.updateReport(reportId, request);
        return ApiResponse.success(response);
    }

    /**
     * 提交评分
     */
    @PostMapping("/{reportId}/rate")
    public ApiResponse<Void> rateReport(
            @PathVariable String reportId,
            @RequestBody Map<String, Object> body) {
        int rating = body.get("rating") instanceof Number ? ((Number) body.get("rating")).intValue() : 0;
        reportService.rateReport(reportId, rating);
        return ApiResponse.success();
    }

    /**
     * 同步清单状态
     */
    @PostMapping("/{reportId}/checklist")
    public ApiResponse<Void> syncChecklist(
            @PathVariable String reportId,
            @RequestBody Map<String, Object> body) {
        reportService.syncChecklist(reportId, body);
        return ApiResponse.success();
    }

    /**
     * 下载报告文件（Word或PPT）
     *
     * @param reportId 报告ID
     * @param format   格式：word 或 ppt
     * @return 文件流
     */
    @GetMapping("/{reportId}/download")
    public ResponseEntity<byte[]> downloadReport(
            @PathVariable String reportId,
            @RequestParam(defaultValue = "word") String format) {
        try {
            String filePath = reportService.downloadReport(reportId, format);

            // 尝试从文件系统读取真实文件
            java.io.File file = new java.io.File(filePath);
            if (file.exists()) {
                byte[] content = java.nio.file.Files.readAllBytes(file.toPath());
                String filename = "report." + ("ppt".equalsIgnoreCase(format) ? "pptx" : "docx");
                MediaType mediaType = "ppt".equalsIgnoreCase(format)
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.valueOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filename + "\"")
                        .contentType(mediaType)
                        .contentLength(content.length)
                        .body(content);
            }

            // 文件尚未生成，返回报告 JSON 作为降级
            log.warn("报告文件尚未生成, reportId: {}, format: {}, path: {}", reportId, format, filePath);
            ReportDetailResponse report = reportService.getReport(reportId);
            Object raw = report.getContentJson();
            String fallbackContent = raw != null ? raw.toString() : "{}";
            byte[] fallback = fallbackContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"report_" + reportId.substring(0, 8) + ".json\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(fallback);

        } catch (Exception e) {
            log.error("下载报告失败, reportId: {}, format: {}", reportId, format, e);
            byte[] errorMsg = "{\"error\":\"报告下载失败，请稍后重试\"}".getBytes();
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(errorMsg);
        }
    }
}
