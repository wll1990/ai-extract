package com.aiextract;

import com.aiextract.config.OssConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * AI经验萃取平台主启动类
 *
 * <p>负责初始化Spring Boot应用上下文，自动装配所有子模块，
 * 包括Web层、安全层、数据访问层和AI服务集成层。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Slf4j
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(OssConfig.class)
public class AiExtractApplication {

    /**
     * WebClient Bean（用于调用Python AI服务）
     *
     * @return WebClient实例
     */
    @Bean
    public WebClient webClient() {
        // 默认 256KB 会把长文档解析结果直接截断成 "AI 服务不可用"。放到 32MB，
        // 足够接收 50MB 文件解析出的文本（字符上限在业务层另行校验）。
        return WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    /**
     * 应用程序主入口
     *
     * @param args 命令行启动参数
     */
    public static void main(String[] args) {
        log.info("AI经验萃取平台正在启动...");
        SpringApplication.run(AiExtractApplication.class, args);
        log.info("AI经验萃取平台启动完成");
    }
}
