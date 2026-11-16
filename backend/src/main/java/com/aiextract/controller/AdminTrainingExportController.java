package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.model.ExportJob;
import com.aiextract.service.TrainingExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 培训导出 —— 建任务、查状态、下载。
 *
 * <p>导出范围由 Service 的 assertCanReview 判定；下载再校验企业归属。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@RestController
@RequestMapping("/admin/training")
@RequiredArgsConstructor
public class AdminTrainingExportController {

    private final TrainingExportService exportService;

    @PostMapping("/cohorts/{id}/export")
    public ApiResponse<Map<String, Object>> request(@PathVariable UUID id) {
        return ApiResponse.success(exportService.toView(exportService.requestCohortExport(id)));
    }

    /** 查询导出状态：只读，不生成下载地址、不写审计（轮询会调用上百次）。 */
    @GetMapping("/exports/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable UUID id) {
        ExportJob job = exportService.get(id);
        return ApiResponse.success(exportService.toView(job));
    }

    /** 真实下载入口：每次调用生成一次短时地址并写一条审计。 */
    @GetMapping("/exports/{id}/download")
    public ResponseEntity<?> download(@PathVariable UUID id) {
        TrainingExportService.Download download = exportService.download(id);
        if (download.url() != null) {
            // 前端拿到 URL 后自行跳转，避免服务端 302 把浏览器带到一个未带审计的地址
            return ResponseEntity.ok(Map.of("url", download.url()));
        }
        Path path = download.localPath();
        if (path == null || !Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"training-cohort-" + id + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(resource);
    }
}
