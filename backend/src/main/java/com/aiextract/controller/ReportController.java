package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.common.ErrorMessages;
import com.aiextract.dto.ReportDetailResponse;
import com.aiextract.dto.ReportListResponse;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.Report;
import com.aiextract.repository.ReportRepository;
import com.aiextract.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;

/**
 * 报告控制器
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
    private final ReportRepository reportRepository;
    private final com.aiextract.util.JwtUtil jwtUtil;

    private static final String SHARE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int SHARE_CODE_LEN = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private String getToken() {
        return (String) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication().getCredentials();
    }

    private UUID getCurrentUserId() {
        return jwtUtil.getUserIdFromToken(getToken());
    }

    // ════════════════════════════════════════════════════════════════
    // 列表 & 详情
    // ════════════════════════════════════════════════════════════════

    @GetMapping
    public ApiResponse<Page<ReportListResponse>> getReports(
            @RequestParam(required = false) String spaceId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false, defaultValue = "createdAt") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) {
        Page<ReportListResponse> reports = reportService.getReports(spaceId, keyword, tag, sort, page, size);
        return ApiResponse.success(reports);
    }

    @GetMapping("/{reportId}")
    public ApiResponse<ReportDetailResponse> getReport(@PathVariable String reportId) {
        ReportDetailResponse response = reportService.getReport(reportId);
        return ApiResponse.success(response);
    }

    // ════════════════════════════════════════════════════════════════
    // HTML 查看 & 下载（内网）
    // ════════════════════════════════════════════════════════════════

    @GetMapping(value = "/{reportId}/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> getReportHtml(@PathVariable String reportId) {
        UUID id = UUID.fromString(reportId);
        Report report = reportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.REPORT_NOT_FOUND));
        if (report.getHtmlPath() == null || report.getHtmlPath().isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("<html><body><h1>报告尚未生成</h1><p>请等待萃取完成后刷新页面。</p></body></html>");
        }
        try {
            String html = Files.readString(Path.of(report.getHtmlPath()), StandardCharsets.UTF_8);
            // 认证接口不再注入工具栏 — ReportViewer 已提供分享/下载/导出按钮
            return ResponseEntity.ok(html);
        } catch (Exception e) {
            log.error("读取报告 HTML 文件失败, reportId: {}, path: {}", reportId, report.getHtmlPath(), e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("<html><body><h1>报告文件读取失败</h1></body></html>");
        }
    }

    @GetMapping("/{reportId}/download")
    public ResponseEntity<byte[]> downloadReportHtml(@PathVariable String reportId) {
        UUID id = UUID.fromString(reportId);
        Report report = reportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.REPORT_NOT_FOUND));
        if (report.getHtmlPath() == null || report.getHtmlPath().isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND.value(), "报告文件尚未生成");
        }
        try {
            byte[] content = Files.readAllBytes(Path.of(report.getHtmlPath()));
            String filename = (report.getTitle() != null ? report.getTitle() : "report") + ".html";
            return ResponseEntity.ok()
                    .header("Content-Disposition", encodeContentDisposition(filename))
                    .contentType(MediaType.TEXT_HTML)
                    .body(content);
        } catch (Exception e) {
            log.error("下载报告文件失败, reportId: {}", reportId, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "文件读取失败");
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 分享
    // ════════════════════════════════════════════════════════════════

    @PostMapping("/{reportId}/share")
    public ApiResponse<Map<String, Object>> shareReport(@PathVariable String reportId) {
        UUID id = UUID.fromString(reportId);
        Report report = reportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND.value(), ErrorMessages.REPORT_NOT_FOUND));

        if (report.getHtmlPath() == null || report.getHtmlPath().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST.value(), "报告尚未生成，无法分享");
        }

        // 已有 shareCode → 直接返回
        if (report.getShareCode() != null && !report.getShareCode().isEmpty()
                && Boolean.TRUE.equals(report.getShareEnabled())) {
            return ApiResponse.success(Map.of(
                    "shareCode", report.getShareCode(),
                    "shareUrl", "/api/v1/public/reports/" + report.getShareCode()));
        }

        // 生成 shareCode，唯一索引 → saveAndFlush 即时检测冲突
        for (int retry = 0; retry < 3; retry++) {
            String code = generateShareCode();
            report.setShareCode(code);
            report.setShareEnabled(true);
            try {
                reportRepository.saveAndFlush(report);
                return ApiResponse.success(Map.of(
                        "shareCode", code,
                        "shareUrl", "/api/v1/public/reports/" + code));
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (retry == 2) {
                    log.error("生成分享码冲突(重试耗尽), reportId: {}", reportId, e);
                    throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "生成分享码失败，请重试");
                }
                // 冲突 → 刷新后重试
                report = reportRepository.findById(id).orElseThrow();
            }
        }
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR.value(), "生成分享码失败");
    }

    // ════════════════════════════════════════════════════════════════
    // 评分（保留）
    // ════════════════════════════════════════════════════════════════

    @PostMapping("/{reportId}/rate")
    public ApiResponse<Void> rateReport(
            @PathVariable String reportId,
            @RequestBody Map<String, Object> body) {
        int rating = body.get("rating") instanceof Number ? ((Number) body.get("rating")).intValue() : 0;
        reportService.rateReport(reportId, rating);
        return ApiResponse.success();
    }

    // ════════════════════════════════════════════════════════════════
    // 按 sessionId 获取报告 HTML（保留，不动）
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/by-session/{sessionId}/html")
    public ResponseEntity<?> getReportHtmlBySession(@PathVariable String sessionId) {
        UUID userId = jwtUtil.getUserIdFromToken(getToken());
        ReportService.ReportHtmlResult result = reportService.getReportHtmlBySession(
                UUID.fromString(sessionId), userId);
        if (!result.ready()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("ready", false, "grains", result.grains(), "scenes", result.scenes(),
                            "needGrains", result.needGrains(), "needScenes", result.needScenes()));
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(result.html());
    }

    // ════════════════════════════════════════════════════════════════
    // helper
    // ════════════════════════════════════════════════════════════════

    /** RFC 5987 编码文件名，支持中文等非 ASCII 字符 */
    static String encodeContentDisposition(String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String ascii = filename.replaceAll("[^\\x20-\\x7E]", "_");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    private String generateShareCode() {
        StringBuilder sb = new StringBuilder(SHARE_CODE_LEN);
        for (int i = 0; i < SHARE_CODE_LEN; i++) {
            sb.append(SHARE_CHARS.charAt(RANDOM.nextInt(SHARE_CHARS.length())));
        }
        return sb.toString();
    }

    /** 注入分享+下载浮动工具栏 */
    static String injectToolbar(String html, String reportId, String shareUrl) {
        String barCss = """
            <style>
            #__report_bar{position:fixed;top:16px;right:16px;z-index:9999;display:flex;gap:8px;font-family:-apple-system,BlinkMacSystemFont,sans-serif}
            .__rb_btn{padding:8px 16px;border-radius:8px;cursor:pointer;font-size:13px;font-weight:500;border:none}
            .__rb_share{background:#2563eb;color:#fff}
            .__rb_share:hover{background:#1d4ed8}
            .__rb_dl,.__rb_pdf{background:#fff;color:#374151;border:1px solid #d1d5db!important;text-decoration:none}
            .__rb_dl:hover,.__rb_pdf:hover{background:#f9fafb}
            .__rb_overlay{display:none;position:fixed;inset:0;z-index:99999;background:rgba(0,0,0,.4);align-items:center;justify-content:center}
            .__rb_overlay.show{display:flex}
            .__rb_modal{background:#fff;border-radius:16px;padding:24px;max-width:360px;width:90%%;text-align:center;box-shadow:0 20px 60px rgba(0,0,0,.15)}
            .__rb_modal h3{font-size:18px;font-weight:700;color:#111827;margin-bottom:16px}
            .__rb_qr{display:inline-block;padding:8px;background:#fff;border:1px solid #e5e7eb;border-radius:12px;margin-bottom:12px}
            .__rb_url{display:flex;gap:8px;margin-bottom:12px}
            .__rb_url input{flex:1;padding:6px 10px;border:1px solid #d1d5db;border-radius:8px;font-size:12px;color:#374151;outline:none}
            .__rb_copy{background:#2563eb;color:#fff;border:none;padding:6px 14px;border-radius:8px;cursor:pointer;font-size:12px;white-space:nowrap}
            .__rb_close{display:block;width:100%%;padding:8px;border:1px solid #d1d5db;border-radius:8px;background:#fff;color:#6b7280;cursor:pointer;font-size:13px;margin-top:8px}
            </style>
            """;

        String bar;
        if (shareUrl != null) {
            String fullUrl = shareUrl; // 公开页 shareUrl 已经是完整路径
            bar = barCss + """
                <div id="__report_bar">
                <button class="__rb_btn __rb_share" onclick="document.getElementById('__rb_overlay').classList.add('show')">分享</button>
                <a class="__rb_btn __rb_dl" href="%s/download">下载报告</a>
                <button class="__rb_btn __rb_pdf" onclick="window.print()">导出 PDF</button>
                </div>
                <div id="__rb_overlay" class="__rb_overlay" onclick="this.classList.remove('show')">
                <div class="__rb_modal" onclick="event.stopPropagation()">
                <h3>分享萃取报告</h3>
                <div class="__rb_qr"><img src="https://api.qrserver.com/v1/create-qr-code/?size=160x160&data=%s" width="160" height="160" alt="QR"></div>
                <div class="__rb_url"><input id="__rb_input" value="%s" readonly><button class="__rb_copy" onclick="var i=document.getElementById('__rb_input');i.select();i.setSelectionRange(0,99999);document.execCommand('copy');this.textContent='已复制'">复制链接</button></div>
                <button class="__rb_close" onclick="document.getElementById('__rb_overlay').classList.remove('show')">关闭</button>
                </div></div>
                """.formatted(shareUrl, shareUrl, fullUrl, fullUrl);
        } else {
            bar = barCss + """
                <div id="__report_bar">
                <button class="__rb_btn __rb_share" id="__share_btn" onclick="var b=this;b.textContent='...';b.disabled=true;fetch('/api/v1/reports/%s/share',{method:'POST',credentials:'include'}).then(r=>r.json()).then(d=>{var u=location.origin+d.data.shareUrl;document.getElementById('__rb_input').value=u;document.getElementById('__rb_qr_img').src='https://api.qrserver.com/v1/create-qr-code/?size=160x160&data='+encodeURIComponent(u);document.getElementById('__rb_overlay').classList.add('show');b.textContent='分享';b.disabled=false}).catch(()=>{alert('分享失败');b.textContent='分享';b.disabled=false})">分享</button>
                <a class="__rb_btn __rb_dl" href="/api/v1/reports/%s/download">下载报告</a>
                <button class="__rb_btn __rb_pdf" onclick="window.print()">导出 PDF</button>
                </div>
                <div id="__rb_overlay" class="__rb_overlay" onclick="this.classList.remove('show')">
                <div class="__rb_modal" onclick="event.stopPropagation()">
                <h3>分享萃取报告</h3>
                <div class="__rb_qr"><img id="__rb_qr_img" src="" width="160" height="160" alt="QR"></div>
                <div class="__rb_url"><input id="__rb_input" value="" readonly><button class="__rb_copy" onclick="var i=document.getElementById('__rb_input');i.select();i.setSelectionRange(0,99999);document.execCommand('copy');this.textContent='已复制'">复制链接</button></div>
                <button class="__rb_close" onclick="document.getElementById('__rb_overlay').classList.remove('show')">关闭</button>
                </div></div>
                """.formatted(reportId, reportId, reportId);
        }
        int idx = html.lastIndexOf("</body>");
        if (idx > 0) {
            return html.substring(0, idx) + bar + html.substring(idx);
        }
        return html + bar;
    }
}
