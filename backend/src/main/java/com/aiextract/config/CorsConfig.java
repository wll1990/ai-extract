package com.aiextract.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * CORS 跨域配置
 *
 * <p>允许前端跨域访问后端 API，同时允许 IM 平台回调接口的跨域请求。
 * 允许的来源列表通过 app.cors.allowed-origins 配置。</p>
 *
 * <p>提供两个 bean：CorsFilter（通用 Spring Web filter）和
 * CorsConfigurationSource（供 Spring Security .cors() 使用），
 * 确保 preflight OPTIONS 请求不被 Security 拦截。</p>
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
        config.setAllowedOrigins(allowedOrigins);
        config.addAllowedMethod("GET");
        config.addAllowedMethod("POST");
        config.addAllowedMethod("PUT");
        config.addAllowedMethod("DELETE");
        config.addAllowedMethod("OPTIONS");
        config.addAllowedHeader("Authorization");
        config.addAllowedHeader("Content-Type");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        return config;
    }

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildConfig());
        return new CorsFilter(source);
    }

    @Bean
    public org.springframework.web.cors.CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildConfig());
        return source;
    }
}
