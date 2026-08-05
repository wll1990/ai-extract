package com.aiextract.controller;

import com.aiextract.model.Report;
import com.aiextract.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 公开报告控制器 — 无需登录即可查看/下载分享的报告。
 *
 * @author AI Extract Team
 * @since 2026-08-03
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PublicReportController {

    private final ReportRepository reportRepository;

    @GetMapping(value = "/public/reports/{shareCode}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> viewSharedReport(@PathVariable String shareCode) {
        Report report = reportRepository.findByShareCode(shareCode).orElse(null);
        if (report == null || !Boolean.TRUE.equals(report.getShareEnabled())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("<html><body><h1>报告不存在或已关闭分享</h1></body></html>");
        }
        if (report.getHtmlPath() == null || report.getHtmlPath().isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("<html><body><h1>报告尚未生成</h1><p>请稍后刷新。</p></body></html>");
        }
        try {
            String html = Files.readString(Path.of(report.getHtmlPath()), StandardCharsets.UTF_8);
            int vc = report.getViewCount() != null ? report.getViewCount() : 0;
            report.setViewCount(vc + 1);
            reportRepository.save(report);
            html = ReportController.injectToolbar(html, null, "/api/v1/public/reports/" + shareCode);
            return ResponseEntity.ok(html);
        } catch (Exception e) {
            log.error("读取公开报告 HTML 失败, shareCode: {}, path: {}", shareCode, report.getHtmlPath(), e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("<html><body><h1>报告文件读取失败</h1></body></html>");
        }
    }

    @GetMapping("/public/reports/{shareCode}/download")
    public ResponseEntity<byte[]> downloadSharedReport(@PathVariable String shareCode) {
        Report report = reportRepository.findByShareCode(shareCode).orElse(null);
        if (report == null || !Boolean.TRUE.equals(report.getShareEnabled())) {
            throw new RuntimeException("报告不存在或已关闭分享");
        }
        if (report.getHtmlPath() == null || report.getHtmlPath().isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("报告文件尚未生成".getBytes());
        }
        try {
            byte[] content = Files.readAllBytes(Path.of(report.getHtmlPath()));
            String filename = (report.getTitle() != null ? report.getTitle() : "report") + ".html";
            return ResponseEntity.ok()
                    .header("Content-Disposition", ReportController.encodeContentDisposition(filename))
                    .contentType(MediaType.TEXT_HTML)
                    .body(content);
        } catch (Exception e) {
            log.error("下载公开报告失败, shareCode: {}", shareCode, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
