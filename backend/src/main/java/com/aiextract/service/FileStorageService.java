package com.aiextract.service;

import com.aiextract.common.UploadLimits;
import com.aiextract.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 文件存储服务 —— 上传文件落阿里云 OSS，返回对象 key（供 ai-service 经预签名 URL 解析）。
 *
 * <p>知识/案例上传共用的文件保存逻辑：格式/大小校验 + 按「年月」分目录 + 随机文件名。
 * 不再落本地磁盘（多实例 / backend 与 ai-service 分离时本地磁盘失效）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final OssService ossService;

    /**
     * 保存上传文件到 OSS。
     *
     * @param file   上传文件
     * @param subdir 存储子目录（如 knowledge / case），作为 OSS 对象 key 的第一段
     * @return OSS 对象 key（如 knowledge/2026-10/{uuid}_file.txt），供后续生成预签名 URL 解析
     */
    public String saveFile(MultipartFile file, String subdir) {
        String originalName = requireName(file);
        if (!UploadLimits.isDocumentAllowed(originalName)) {
            throw new BusinessException(400,
                    "不支持的文件格式，支持 " + UploadLimits.documentExtensionHint());
        }
        return save(file, subdir);
    }

    /**
     * 保存分身素材 / 萃取师资料到 OSS（文档 + 图片 + 音频）。
     * 素材、专家资料与知识/案例一样不允许再落本地磁盘。
     */
    public String saveMaterialFile(MultipartFile file, String subdir) {
        String originalName = requireName(file);
        if (!UploadLimits.isMaterialAllowed(originalName)) {
            throw new BusinessException(400,
                    "不支持的文件格式，支持 " + UploadLimits.materialExtensionHint());
        }
        return save(file, subdir);
    }

    /**
     * 直接把字节写入 OSS，用于存量本地文件迁移。
     */
    public String saveBytes(byte[] content, String subdir, String extension, String contentType) {
        String safeExtension = extension != null && extension.matches("\\.[a-z0-9]{1,10}")
                ? extension : "";
        String month = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String objectKey = subdir + "/" + month + "/" + UUID.randomUUID() + safeExtension;
        ossService.upload(objectKey, content,
                contentType != null && !contentType.isBlank() ? contentType : "application/octet-stream");
        return objectKey;
    }

    /** 删除 OSS 对象；空 key 或本地遗留路径忽略。 */
    public void delete(String objectKey) {
        if (objectKey == null || objectKey.isBlank()
                || objectKey.startsWith("/") || objectKey.startsWith("./")
                || objectKey.startsWith("../") || objectKey.startsWith("data/")
                || objectKey.startsWith("file:")
                || objectKey.startsWith("http://") || objectKey.startsWith("https://")
                || objectKey.startsWith("text://") || objectKey.startsWith("interview://")
                || objectKey.matches("^[A-Za-z]:[\\\\/].*")) {
            return;
        }
        try {
            ossService.delete(objectKey);
        } catch (RuntimeException e) {
            log.warn("OSS 删除失败 key={}: {}", objectKey, e.getMessage());
        }
    }

    private String requireName(MultipartFile file) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(400, "文件名不能为空");
        }
        return originalName;
    }

    private String save(MultipartFile file, String subdir) {
        String originalName = file.getOriginalFilename();
        if (file.getSize() > UploadLimits.MAX_FILE_SIZE) {
            throw new BusinessException(400, "文件不能超过 " + UploadLimits.MAX_FILE_SIZE_MB + "MB");
        }
        String safeExtension = UploadLimits.extensionOf(originalName);
        String month = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        // 对象 key 只用随机名 + 白名单扩展名，不把用户文件名拼进路径（防路径穿越）
        String objectKey = subdir + "/" + month + "/" + UUID.randomUUID() + safeExtension;
        try {
            ossService.upload(objectKey, file);
        } catch (RuntimeException e) {
            log.error("OSS 上传失败 subdir={} fileName={}", subdir, originalName, e);
            throw new BusinessException(500, "文件上传失败，请重试");
        }
        return objectKey;
    }

    /**
     * 生成原始文档的短时效下载地址，供详情页「查看原始文档」使用。
     *
     * @param objectKey     OSS 对象 key
     * @param expireSeconds 有效期（秒）
     */
    public String presignedUrl(String objectKey, long expireSeconds) {
        return ossService.generatePresignedUrl(objectKey, expireSeconds);
    }

    /** 读取 OSS 对象内容 */
    public byte[] read(String objectKey) {
        return ossService.read(objectKey);
    }
}
