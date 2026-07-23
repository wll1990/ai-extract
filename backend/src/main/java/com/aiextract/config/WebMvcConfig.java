package com.aiextract.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源映射 — 上传的头像等文件通过 HTTP 访问。
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
