package com.aiextract.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云 OSS 配置属性。
 *
 * @author AI Extract Team
 * @since 2026-08-12
 */
@ConfigurationProperties(prefix = "storage.oss")
public record OssConfig(
        String endpoint,
        String bucket,
        String accessKeyId,
        String accessKeySecret) {
}
