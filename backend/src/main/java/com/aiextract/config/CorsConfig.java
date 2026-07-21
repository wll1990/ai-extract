package com.aiextract.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS 跨域配置 — 供 Spring Security {@code .cors()} 使用。
 *
 * <p>只注册 CorsConfigurationSource bean，不另注册 Servlet 层 CorsFilter，
 * 避免 Servlet 层和 Security 层双重 CORS 处理导致 preflight 请求失败。</p>
 *
 * <p>生产环境优先走 nginx 反代同域部署，无需 CORS；独立部署时在
 * application.yml 的 {@code allowed-origins} 中追加生产域名即可。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Configuration
public class CorsConfig {

    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    private CorsConfiguration buildConfig() {
        CorsConfiguration config = new CorsConfiguration();
        // 开发环境多端口（3000/3001）走 setAllowedOrigins 精确匹配
        config.setAllowedOrigins(allowedOrigins);
        // allowedOriginPatterns 兜底动态端口，Spring 5.3+
        config.addAllowedOriginPattern("*");
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        return config;
    }

    /**
     * CorsConfigurationSource — 供 Spring Security .cors() 使用。
     * 不额外注册 Servlet 层 CorsFilter，避免双重 CORS 处理。
     */
    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildConfig());
        return source;
    }
}
