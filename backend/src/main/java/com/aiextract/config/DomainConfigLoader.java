package com.aiextract.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 领域配置加载器 — 支持层级继承。
 *
 * <p>领域 ID 用点号分隔层级：sales.b2b_enterprise → domain/sales/b2b_enterprise.yml。
 * 通过 extends 字段声明继承父级配置，只写差异项。</p>
 *
 * <p>Jackson YAML + SNAKE_CASE 策略自动映射：
 * YAML 的 {@code role_label} ↔ Java 的 {@code roleLabel}。</p>
 *
 * <p>用法：
 * <pre>{@code
 * DomainConfig config = loader.load("finance.secondary_market");
 * String role = config.getDomain().getRoleLabel(); // "研究员"
 * }</pre>
 */
@Slf4j
@Component
public class DomainConfigLoader {

    private final Map<String, DomainConfig> cache = new ConcurrentHashMap<>();
    private final ObjectMapper yamlMapper;

    private Path externalDir;

    public DomainConfigLoader() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @PostConstruct
    public void init() {
        String dir = System.getenv("PROMPTS_DIR");
        if (dir != null && !dir.isEmpty()) {
            Path promptsDir = Paths.get(dir);
            externalDir = promptsDir.resolve("domain");
            if (Files.isDirectory(externalDir)) {
                log.info("DomainConfigLoader 使用外部目录: {}", externalDir.toAbsolutePath());
            } else {
                log.info("外部 PROMPTS_DIR 下无 domain/ 目录，使用 classpath");
                externalDir = null;
            }
        }
    }

    /**
     * 加载领域配置。支持 extends 递归继承。
     *
     * @param domainId 领域 ID，如 "finance.secondary_market"。null 或空返回 null
     */
    public DomainConfig load(String domainId) {
        { if (domainId == null || domainId.isBlank()) return null; }
        final String key = domainId;
        DomainConfig cached = cache.get(key);
        if (cached != null) {

            return cached;

        }

        DomainConfig config = loadFile(domainId);
        if (config == null) {
            log.error("领域配置加载失败: {}", domainId);
            return null;
        }

        // extends 递归加载 + 合并
        if (config.getExtends() != null && !config.getExtends().isBlank()) {
            DomainConfig parent = load(config.getExtends());
            if (parent != null) {
                config = deepMerge(parent, config);
            }
        }

        cache.put(key, config);
        log.info("领域配置已加载: {} (extends={})", domainId, config.getExtends());
        return config;
    }

    /**
     * 从 Skill 安全解析领域 ID。Skill 或 domain 为空时返回 null。
     * 兼容旧数据：sales → sales.b2b_enterprise
     */
    public String resolveDomain(com.aiextract.model.Skill skill) {
        if (skill == null || skill.getDomain() == null || skill.getDomain().isBlank()) {
            return null;
        }
        String d = skill.getDomain();
        if ("sales".equals(d)) return "sales.b2b_enterprise";
        return d;
    }

    // ── internal ──

    private DomainConfig loadFile(String domainId) {
        String path = domainId.replace('.', '/') + ".yml";

        // 1. 优先外部目录
        if (externalDir != null) {
            Path filePath = externalDir.resolve(path);
            if (Files.exists(filePath)) {
                try (InputStream in = Files.newInputStream(filePath)) {
                    DomainConfig config = yamlMapper.readValue(in, DomainConfig.class);
                    log.info("领域配置(外部): {} ({})", domainId, filePath);
                    return config;
                } catch (IOException e) {
                    log.warn("读取外部领域配置失败: {}", filePath, e);
                }
            }
        }

        // 2. 回退 classpath
        try {
            ClassPathResource resource = new ClassPathResource("domain/" + path);
            if (resource.exists()) {
                try (InputStream in = resource.getInputStream()) {
                    DomainConfig config = yamlMapper.readValue(in, DomainConfig.class);
                    log.info("领域配置(classpath): {}", domainId);
                    return config;
                }
            }
        } catch (IOException e) {
            log.warn("读取 classpath 领域配置失败: domain/{}", path, e);
        }

        return null;
    }

    /**
     * 深度合并：子配置覆盖父配置。对每个顶层字段做 null-check。
     */
    private DomainConfig deepMerge(DomainConfig parent, DomainConfig child) {
        if (child.getDomain() == null) child.setDomain(parent.getDomain());
        if (child.getAcceptance() == null) child.setAcceptance(parent.getAcceptance());
        else child.setAcceptance(mergeAcceptance(parent.getAcceptance(), child.getAcceptance()));
        if (child.getPrecheck() == null) child.setPrecheck(parent.getPrecheck());
        else child.setPrecheck(mergePrecheck(parent.getPrecheck(), child.getPrecheck()));
        if (child.getPipeline() == null) child.setPipeline(parent.getPipeline());
        if (child.getChat() == null) child.setChat(parent.getChat());
        return child;
    }

    private DomainConfig.AcceptanceConfig mergeAcceptance(DomainConfig.AcceptanceConfig parent, DomainConfig.AcceptanceConfig child) {
        if (parent == null) {

            return child;

        }
        if (child.getBusinessWhitelist() == null) child.setBusinessWhitelist(parent.getBusinessWhitelist());
        if (child.getSalesKeywords() == null) child.setSalesKeywords(parent.getSalesKeywords());
        if (child.getMarketingSignals() == null) child.setMarketingSignals(parent.getMarketingSignals());
        if (child.getAiSignals() == null) child.setAiSignals(parent.getAiSignals());
        return child;
    }

    private DomainConfig.PreCheckConfig mergePrecheck(DomainConfig.PreCheckConfig parent, DomainConfig.PreCheckConfig child) {
        if (parent == null) {
            return child;
        }
        if (child.getKeywordGroups() == null) child.setKeywordGroups(parent.getKeywordGroups());
        if (child.getObjectionPatterns() == null) child.setObjectionPatterns(parent.getObjectionPatterns());
        if (child.getSceneMapping() == null) child.setSceneMapping(parent.getSceneMapping());
        if (child.getNoisePatterns() == null) child.setNoisePatterns(parent.getNoisePatterns());
        if (child.getLabels() == null) child.setLabels(parent.getLabels());
        return child;
    }

    /** 清除所有缓存。 */
    public void clearCache() {
        cache.clear();
        log.info("领域配置缓存已清除");
    }

    /** 失效指定领域配置缓存。 */
    public void invalidate(String domainId) {
        cache.remove(domainId);
        log.info("领域配置缓存已失效: {}", domainId);
    }
}
