package com.aiextract.service.export;

import com.aiextract.service.OssService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * OSS 存储 —— 多实例部署使用。
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Component
public class OssExportStorage implements ExportStorage {

    private final OssService ossService;

    @Value("${app.training.export.presign-seconds:900}")
    private long presignSeconds;

    public OssExportStorage(OssService ossService) {
        this.ossService = ossService;
    }

    @Override
    public String store(String key, byte[] content, String contentType) {
        return ossService.upload(key, content, contentType);
    }

    @Override
    public String presignedUrl(String key) {
        return ossService.generatePresignedUrl(key, presignSeconds);
    }

    @Override
    public Path localPath(String key) {
        return null;
    }
}
