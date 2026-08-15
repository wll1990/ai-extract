package com.aiextract.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源映射 — 本地文件兜底。
 * 头像已切 OSS，此处仅供素材/文档等本地文件访问。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${storage.local.path:data/files}")
    private String storageBasePath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // /files/avatars/** → data/files/avatars/
        String avatarLocation = "file:" + storageBasePath + "/avatars/";
        registry.addResourceHandler("/files/avatars/**")
                .addResourceLocations(avatarLocation);
    }
}
