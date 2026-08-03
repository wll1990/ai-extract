package com.aiextract.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板加载器 — 支持领域感知，L1 本地 + L2 Redis 双层缓存。
 *
 * <p><b>缓存架构</b>
 * <pre>
 * 读取: 本地 ConcurrentHashMap → Redis → 磁盘 → 回写双层缓存
 * 变更: invalidate(name) 同时清除本地和 Redis
 * Redis 不可用时自动降级为本地缓存
 * </pre>
 *
 * <p>加载优先级：
 * <ol>
 *   <li>外部目录 domain/{domain}/{name}（PROMPTS_DIR 环境变量）</li>
 *   <li>外部目录 {name}</li>
 *   <li>classpath domain/{domain}/{name}</li>
 *   <li>classpath {name}</li>
 * </ol>
  * @author AI Extract Team
 */
@Slf4j
@Component
public class PromptLoader {

    private static final int SPLIT_LIMIT = 2;
    private static final String VAR_PREFIX = "{";
    private static final String VAR_SUFFIX = "}";

    private static final String REDIS_KEY_PREFIX = "prompt:";
    private static final Duration REDIS_TTL = Duration.ofHours(1);
    /** classpath 资源无 mtime，存 0 标记跳过变更检查 */
    private static final long MTIME_CLASSPATH = 0;

    private final Map<String, CacheEntry> localCache = new ConcurrentHashMap<>();
    private Path promptsDir;

    @Autowired
    @Lazy
    private DomainConfigLoader domainConfigLoader;

    /** Redis 可选：未配置时自动降级为纯本地缓存 */
    @Autowired(required = false)
    private StringRedisTemplate redis;

    @PostConstruct
    public void init() {
        String dir = System.getenv("PROMPTS_DIR");
        if (dir != null && !dir.isEmpty()) {
            promptsDir = Paths.get(dir);
            if (Files.isDirectory(promptsDir)) {
                log.info("PromptLoader 使用外部目录: {} (Redis={})",
                        promptsDir.toAbsolutePath(), redis != null ? "enabled" : "disabled");
            } else {
                log.warn("PROMPTS_DIR 不存在: {}, 回退 classpath", dir);
                promptsDir = null;
            }
        } else {
            log.info("PromptLoader 使用 classpath:/prompts/ (Redis={})",
                    redis != null ? "enabled" : "disabled");
        }
        // 启动时清缓存，确保模板更新后重启能读到最新版本
        clearCache();
    }

    // ============================================================
    // 公开 API
    // ============================================================

    /** 加载模板原始内容，无领域感知。 */
    public String load(String name) {
        return loadInternal(name, null);
    }

    /** 加载模板并替换变量，不注入领域变量。 */
    public String format(String name, Map<String, String> vars) {
        String template = load(name);
        return replaceVars(template, name, vars);
    }

    /**
     * 加载领域模板。优先 domain/{domain}/{name}，不存在则回退 {name}。
     */
    public String load(String name, String domain) {
        if (domain != null && !domain.isBlank()) {
            String domainPath = "domain/" + domain + "/" + name;
            String result = loadInternal(domainPath, domain);
            if (result != null) {

                return result;

            }
        }
        return loadInternal(name, null);
    }

    /** 加载领域模板 + 替换变量 + 注入领域变量。 */
    public String format(String name, Map<String, String> vars, String domain) {
        String template = load(name, domain);
        Map<String, String> merged = injectDomainVars(domain, vars);
        return replaceVars(template, name, merged);
    }

    // ============================================================
    // 缓存管理
    // ============================================================

    /** 清除所有缓存（本地 + Redis）。 */
    public void clearCache() {
        int localSize = localCache.size();
        localCache.clear();
        if (redis != null) {
            try {
                Set<String> keys = redis.keys(REDIS_KEY_PREFIX + "*");
                if (keys != null && !keys.isEmpty()) {
                    redis.delete(keys);
                }
            } catch (Exception e) {
                log.warn("清除 Redis Prompt 缓存失败", e);
            }
        }
        log.info("Prompt 缓存已清除，本地 {} 条", localSize);
    }

    /** 失效指定文件缓存（无领域前缀）。同时清本地和 Redis。 */
    public void invalidate(String name) {
        invalidateKey(name);
    }

    /** 失效指定领域+文件缓存。同时清本地和 Redis。 */
    public void invalidate(String name, String domain) {
        invalidateKey(domain != null ? domain + "/" + name : name);
    }

    private void invalidateKey(String cacheKey) {
        localCache.remove(cacheKey);
        if (redis != null) {
            try {
                redis.delete(REDIS_KEY_PREFIX + cacheKey);
            } catch (Exception e) {
                log.warn("清除 Redis Prompt 缓存失败 key={}", cacheKey, e);
            }
        }
        log.debug("Prompt 缓存已失效: {}", cacheKey);
    }

    // ============================================================
    // 加载核心：L1 → L2 → 磁盘
    // ============================================================

    private String loadInternal(String name, String domain) {
        String cacheKey = domain != null ? domain + "/" + name : name;

        // 1. 外部目录：mtime 感知
        if (promptsDir != null) {
            return loadFromExternal(promptsDir.resolve(name), cacheKey);
        }

        // 2. Classpath：mtime=0，依赖 TTL
        return loadFromClasspath(name, cacheKey);
    }

    /** 从外部目录加载，mtime 变化时自动刷新。 */
    private String loadFromExternal(Path filePath, String cacheKey) {
        try {
            if (!Files.exists(filePath)) {
                return fallbackToClasspath(cacheKey);
                // 外部目录没文件 → 尝试 classpath
            }
            long mtime = Files.getLastModifiedTime(filePath).toMillis();

            // L1: 本地缓存
            CacheEntry local = localCache.get(cacheKey);
            if (local != null && local.mtime == mtime) {
                return local.content;
            }

            // L2: Redis
            if (redis != null) {
                String entry = readFromRedis(cacheKey);
                if (entry != null) {
                    String[] parts = entry.split("\\|", SPLIT_LIMIT);
                    if (parts.length == SPLIT_LIMIT && Long.parseLong(parts[0]) == mtime) {
                        String content = parts[1];
                        localCache.put(cacheKey, new CacheEntry(mtime, content));
                        return content;
                    }
                }
            }

            // 磁盘加载 + 回写缓存
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            localCache.put(cacheKey, new CacheEntry(mtime, content));
            writeToRedis(cacheKey, mtime, content);
            log.debug("Prompt 已加载(磁盘): {} ({} bytes)", cacheKey, content.length());
            return content;
        } catch (IOException e) {
            log.warn("读取外部 Prompt 失败: {}, 尝试缓存回退", filePath, e);
            return fallbackToCache(cacheKey);
        }
    }

    /** 从 classpath 加载，不检查 mtime，依赖 TTL 刷新。 */
    private String loadFromClasspath(String name, String cacheKey) {
        // L1
        CacheEntry local = localCache.get(cacheKey);
        if (local != null) {

            return local.content;

        }

        // L2
        if (redis != null) {
            String entry = readFromRedis(cacheKey);
            if (entry != null) {
                String[] parts = entry.split("\\|", SPLIT_LIMIT);
                if (parts.length == SPLIT_LIMIT) {
                    localCache.put(cacheKey, new CacheEntry(MTIME_CLASSPATH, parts[1]));
                    return parts[1];
                }
            }
        }

        // 磁盘 (classpath)
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + name);
            if (!resource.exists()) {
                log.debug("Prompt 不存在(classpath): {}", name);
                return null;
            }
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            localCache.put(cacheKey, new CacheEntry(MTIME_CLASSPATH, content));
            writeToRedis(cacheKey, MTIME_CLASSPATH, content);
            log.debug("Prompt 已加载(classpath): {} ({} bytes)", cacheKey, content.length());
            return content;
        } catch (IOException e) {
            log.error("加载 Prompt 失败: {}", name, e);
            return null;
        }
    }

    /** 外部文件不存在时回退 classpath。 */
    private String fallbackToClasspath(String cacheKey) {
        // 尝试直接用 classpath 加载相同路径
        return loadFromClasspath(cacheKey, cacheKey);
    }

    /** 磁盘/网络都失败时用本地缓存兜底。 */
    private String fallbackToCache(String cacheKey) {
        CacheEntry local = localCache.get(cacheKey);
        if (local != null) {

            return local.content;

        }
        if (redis != null) {
            String entry = readFromRedis(cacheKey);
            if (entry != null) {
                String[] parts = entry.split("\\|", SPLIT_LIMIT);
                if (parts.length == SPLIT_LIMIT) {

                    return parts[1];

                }
            }
        }
        return null;
    }

    // ============================================================
    // Redis 读写
    // ============================================================

    private void writeToRedis(String cacheKey, long mtime, String content) {
        if (redis == null) {

            return;

        }
        try {
            String value = mtime + "|" + content;
            redis.opsForValue().set(REDIS_KEY_PREFIX + cacheKey, value, REDIS_TTL);
        } catch (Exception e) {
            log.debug("Redis Prompt 缓存写入失败 key={}, 降级为本地缓存", cacheKey);
        }
    }

    private String readFromRedis(String cacheKey) {
        try {
            return redis.opsForValue().get(REDIS_KEY_PREFIX + cacheKey);
        } catch (Exception e) {
            log.debug("Redis Prompt 缓存读取失败 key={}, 降级为本地缓存", cacheKey);
            return null;
        }
    }

    // ============================================================
    // 变量替换 + 领域注入
    // ============================================================

    private String replaceVars(String template, String name, Map<String, String> vars) {
        if (template == null) {

            return "";

        }
        String result = template;
        if (vars != null) {
            for (Map.Entry<String, String> var : vars.entrySet()) {
                result = result.replace(VAR_PREFIX + var.getKey() + VAR_SUFFIX,
                        var.getValue() != null ? var.getValue() : "");
            }
        }
        // 检查是否有未替换的已知变量（忽略文本内容中自然出现的 {xxx}）
        boolean hasUnreplaced = false;
        if (vars != null) {
            for (String key : vars.keySet()) {
                if (result.contains(VAR_PREFIX + key + VAR_SUFFIX)) {
                    hasUnreplaced = true;
                    break;
                }
            }
        }
        if (hasUnreplaced) {
            log.warn("Prompt [{}] 可能包含未替换的占位符", name);
        }
        return result;
    }
    /**
     * injectDomain Vars。
     * @param domain 参数
     * @param Map<String 参数
     * @param vars 参数
     * @return 结果
     */
    private Map<String, String> injectDomainVars(String domain, Map<String, String> vars) {
        Map<String, String> merged = new HashMap<>(16);
        if (domain != null && !domain.isBlank() && domainConfigLoader != null) {
            try {
                DomainConfig dc = domainConfigLoader.load(domain);
                if (dc != null && dc.getDomain() != null) {
                    merged.put("domain.id", orEmpty(dc.getDomain().getId()));
                    merged.put("domain.name", orEmpty(dc.getDomain().getName()));
                    merged.put("domain.role_label", orEmpty(dc.getDomain().getRoleLabel()));
                    merged.put("domain.counterparty_label", orEmpty(dc.getDomain().getCounterpartyLabel()));
                    merged.put("domain.skill_label", orEmpty(dc.getDomain().getSkillLabel()));
                    merged.put("domain.knowledge_unit", orEmpty(dc.getDomain().getKnowledgeUnit()));
                    merged.put("domain.knowledge_unit_plural", orEmpty(dc.getDomain().getKnowledgeUnitPlural()));
                }
            } catch (Exception e) {
                log.warn("注入领域变量失败 domain={}: {}", domain, e.getMessage());
            }
        }
        if (vars != null) {

            merged.putAll(vars);

        }
        return merged;
    }

    private static String orEmpty(String s) { return s != null ? s : ""; }

    private record CacheEntry(long mtime, String content) {}
}
