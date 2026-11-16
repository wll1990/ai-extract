package com.aiextract.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 报告 HTML 存储 —— 新报告写 OSS，存量本地文件读取时兼容。
 *
 * <p>报告原本写 `./data/reports/{id}.html`，多实例部署时读取节点可能没有该文件。
 * 现在统一写 `reports/yyyy-MM/{uuid}.html`，DB 的 `html_path` 存对象 key；
 * 历史值（以 `/`、`./`、`data/` 开头）仍按本地文件读取。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportStorageService {

    private final FileStorageService fileStorageService;

    /** 写报告 HTML，返回 OSS 对象 key */
    public String write(UUID reportId, String html) {
        return fileStorageService.saveBytes(html.getBytes(StandardCharsets.UTF_8),
                "reports", ".html", "text/html;charset=UTF-8");
    }

    /** 读取报告 HTML：OSS key 或存量本地路径 */
    public String read(String htmlPath) {
        byte[] bytes = readBytes(htmlPath);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** 读取报告原始字节（下载用） */
    public byte[] readBytes(String htmlPath) {
        if (htmlPath == null || htmlPath.isBlank()) {
            return null;
        }
        if (isOssKey(htmlPath)) {
            return fileStorageService.read(htmlPath);
        }
        try {
            return Files.readAllBytes(Path.of(htmlPath));
        } catch (Exception e) {
            log.error("读取本地报告 HTML 失败 path={}: {}", htmlPath, e.getMessage());
            throw new RuntimeException("读取报告文件失败", e);
        }
    }

    /** 删除报告 HTML（重新生成/清理时） */
    public void delete(String htmlPath) {
        if (htmlPath != null && isOssKey(htmlPath)) {
            fileStorageService.delete(htmlPath);
        }
    }

    private boolean isOssKey(String ref) {
        return !ref.startsWith("/") && !ref.startsWith("./") && !ref.startsWith("../")
                && !ref.startsWith("data/") && !ref.startsWith("file:")
                && !ref.startsWith("http://") && !ref.startsWith("https://");
    }
}
