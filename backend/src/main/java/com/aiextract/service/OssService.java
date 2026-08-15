package com.aiextract.service;

import com.aiextract.config.OssConfig;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Date;

/**
 * 阿里云 OSS 对象存储服务 — 头像上传 / 删除 / 预签名 URL 生成。
 *
 * <p>Bucket 完全私有（阻止公共访问），前端通过预签名 URL 加载头像。</p>
 * <p>预签名 URL 有效期 365 天，每次重新上传头像自动刷新。</p>
 *
 * @author AI Extract Team
 * @since 2026-08-12
 */
@Slf4j
@Service
public class OssService {

    private final OSS ossClient;
    private final String bucket;

    /** 头像预签名 URL 有效期：365 天 */
    private static final long AVATAR_URL_EXPIRE_SECONDS = 365 * 24 * 3600L;

    public OssService(OssConfig config) {
        this.bucket = config.bucket();
        this.ossClient = new OSSClientBuilder().build(
                config.endpoint(), config.accessKeyId(), config.accessKeySecret());
        log.info("OSS 客户端已初始化 bucket={} endpoint={}", bucket, config.endpoint());
    }

    @PreDestroy
    public void shutdown() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    /**
     * 上传文件到 OSS，返回预签名访问 URL（有效期 365 天）。
     */
    public String upload(String objectKey, byte[] content, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType(contentType);
        metadata.setContentLength(content.length);
        PutObjectRequest req = new PutObjectRequest(bucket, objectKey,
                new ByteArrayInputStream(content), metadata);
        ossClient.putObject(req);
        String url = generatePresignedUrl(objectKey);
        log.info("OSS 上传成功 key={} size={}bytes", objectKey, content.length);
        return url;
    }

    /** 上传 MultipartFile 到 OSS。 */
    public String upload(String objectKey, MultipartFile file) {
        try {
            return upload(objectKey, file.getBytes(),
                    file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        } catch (IOException e) {
            throw new RuntimeException("OSS 上传失败: " + e.getMessage(), e);
        }
    }

    /** 删除 OSS 对象。 */
    public void delete(String objectKey) {
        ossClient.deleteObject(bucket, objectKey);
        log.info("OSS 删除成功 key={}", objectKey);
    }

    /**
     * 生成预签名 GET URL，有效期 365 天。
     * <p>URL 包含 OSSAccessKeyId（公钥标识，非密钥）和 Signature（HMAC 签名），
     * 结构等同于 AWS S3 预签名 URL。</p>
     */
    public String generatePresignedUrl(String objectKey) {
        Date expiration = new Date(System.currentTimeMillis() + AVATAR_URL_EXPIRE_SECONDS * 1000);
        URL url = ossClient.generatePresignedUrl(bucket, objectKey, expiration);
        return url.toString();
    }
}
