package com.aiextract.service;

import com.aiextract.config.QueryGateConfig;
import com.aiextract.model.ChatChunk;
import com.aiextract.repository.SkillMessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Collections;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 统一查询安全门控 — Controller 层和 ChatStreamService 之间的独立拦截层。
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>可剥离</b>：Redis key {@code query_gate:enabled} 设为 "false" 或删除此类即可完全停用，
 *       所有 Controller 中仅需一行 null 检查</li>
 *   <li><b>fail-open</b>：Redis 不可用时全部放行，不阻断业务。限流是兜底而非主控制</li>
 *   <li><b>零 LLM 依赖</b>：全规则引擎，不增加对话延迟</li>
 * </ul>
 *
 * <p>四层门控（短路执行，延迟递增）：
 * <ol>
 *   <li>基础合法性 — 空/长/纯符号（0ms，纯 Java 正则）</li>
 *   <li>内容安全   — 违禁词 Trie + 隐私正则 + Prompt 注入检测（0-1ms）</li>
 *   <li>频率控制   — Redis INCR 固定窗口（1-2ms，fail-open）</li>
 *   <li>每日额度   — DB 每日计数（0-5ms，c_guest + c_user 角色）</li>
 * </ol>
 *
 * <p>userId 和 role 可为 null（IM 回调无 JWT），此时仅执行 Layer 1+2。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryGate {

    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final String REDIS_ENABLED_KEY = "query_gate:enabled";
    private static final String REDIS_BANNED_WORDS_UPDATE_CHANNEL = "query_gate:banned_words:update";

    // Layer 1 — 纯符号/emoji 正则（去除空白后全是标点/符号即拦截）
    private static final Pattern PURE_SYMBOL = Pattern.compile("[\\p{P}\\p{S}\\p{Z}]+");

    private final QueryGateConfig config;
    private final StringRedisTemplate redisTemplate;
    private final SkillMessageRepository skillMessageRepository;
    @Autowired(required = false)
    private MeterRegistry meterRegistry;

    /** 运行时开关快照 — 由 5 秒定时任务刷新，避免每个请求多次 Redis GET。 */
    private volatile boolean runtimeEnabled = true;

    @Value("${app.share.guest-message-limit:5}")
    private int guestMessageLimit;

    @Value("${app.share.c-user-message-limit:100}")
    private int cUserMessageLimit;

    // ── Micrometer counters ──
    private final Map<String, Counter> blockCounters = new ConcurrentHashMap<>();
    private Counter passCounter;

    // ── Trie 树（线程安全，通过 Pub/Sub + 定时刷新更新） ──
    private volatile TrieNode bannedWordsTrie = new TrieNode();

    // ── 预编译的正则 Pattern 列表 ──
    private Pattern phonePattern;
    private Pattern idCardPattern;
    private List<Pattern> promptInjectionPatterns;

    @PostConstruct
    void init() {
        // 从 YAML 配置编译正则
        phonePattern = Pattern.compile(config.getSensitivePatterns().getPhone());
        idCardPattern = Pattern.compile(config.getSensitivePatterns().getIdCard());
        promptInjectionPatterns = config.getSensitivePatterns().getPromptInjection().stream()
            .map(p -> Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
            .toList();

        // Micrometer（可选，未引入 actuator 时跳过）
        if (meterRegistry != null) {
            passCounter = Counter.builder("query_gate_pass_total")
                .description("QueryGate 放行的消息数")
                .register(meterRegistry);
        }

        // 从 Redis 加载违禁词词表
        reloadTrieFromRedis();

        // 启动时初始化运行时开关，避免前 5 秒仍用默认值
        refreshEnabledSwitch();

        // Redis Pub/Sub 即时刷新 — 使用 Spring 内置容器，自动管理连接和重连
        if (redisTemplate.getConnectionFactory() != null) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(redisTemplate.getConnectionFactory());
            container.addMessageListener((MessageListener) (message, pattern) -> {
                log.info("QueryGate 收到词表更新通知，重新加载 Trie");
                reloadTrieFromRedis();
            }, Collections.singletonList(new ChannelTopic(REDIS_BANNED_WORDS_UPDATE_CHANNEL)));
            container.afterPropertiesSet();
            container.start();
        }
    }

    /**
     * 定时从 Redis 全量刷新词表 — Pub/Sub 的兜底。
     * 即使 Pub/Sub 断线，最多 60 秒后也能同步到最新词表。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    void scheduledReloadTrie() {
        reloadTrieFromRedis();
    }

    /** 定时刷新运行时开关；Redis 异常时 fail-open。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5000)
    void refreshEnabledSwitch() {
        try {
            String v = redisTemplate.opsForValue().get(REDIS_ENABLED_KEY);
            runtimeEnabled = !"false".equals(v);
        } catch (Exception e) {
            runtimeEnabled = true; // fail-open
        }
    }

    /** 请求内判定：本地快照 + YAML 开关，零 Redis 往返。 */
    private boolean isEnabled() {
        return runtimeEnabled && config.isEnabled();
    }

    // ============================================================
    // 公共入口
    // ============================================================

    /**
     * 统一门控入口 — 四层短路检查。
     *
     * @param message  用户原始消息
     * @param userId   用户 ID（可为 null，IM 回调无 JWT，此时跳过 Layer 3+4）
     * @param role     用户角色（c_guest / c_user / employee / null）
     * @param clientIp 客户端 IP
     * @return null = 放行；非 null = 拦截 Flux，调用方应直接返回给客户端
     */
    @Nullable
    public Flux<ChatChunk> audit(@Nullable String message,
                                  @Nullable UUID userId,
                                  @Nullable String role,
                                  String clientIp) {
        // ── 运行时开关（5 秒刷新的本地快照 + YAML 开关） ──
        if (!isEnabled()) return null;

        // ── Layer 1: 基础合法性（0ms，全角色） ──
        Flux<ChatChunk> l1 = auditBasic(message);
        if (l1 != null) return l1;

        // ── Layer 2: 内容安全（0-1ms，全角色） ──
        Flux<ChatChunk> l2 = auditContent(message);
        if (l2 != null) return l2;

        // ── Layer 3: 频率控制（1-2ms，需要 userId/role/ip） ──
        Flux<ChatChunk> l3 = auditRate(userId, role, clientIp);
        if (l3 != null) return l3;

        // ── Layer 4: 每日免费额度（0-5ms，c_guest + c_user） ──
        Flux<ChatChunk> l4 = auditQuota(userId, role);
        if (l4 != null) return l4;

        // TODO: 引入 Micrometer 后移除此 null 保护
        //       1. pom.xml 添加 spring-boot-starter-actuator 依赖
        //       2. application.yml 配置 management.metrics.export.* 上报目标（Prometheus / InfluxDB 等）
        //       3. 配置 Grafana 仪表盘：query_gate_pass_total（放行量）、query_gate_block_*（拦截量）
        //       4. MeterRegistry 的 @Autowired(required = false) 可改为必选注入
        if (passCounter != null) passCounter.increment();
        return null;
    }

    /**
     * 简化版校验 — 仅 Layer 1+2，用于非 Flux 路径（如 IM 同步回调）。
     *
     * @param message 用户原始消息
     * @return null = 放行；非 null = 错误消息文本
     */
    @Nullable
    public String auditSimple(@Nullable String message) {
        if (!isEnabled()) return null;

        Flux<ChatChunk> blocked = auditBasic(message);
        if (blocked != null) {
            return errorText(blocked);
        }
        blocked = auditContent(message);
        if (blocked != null) {
            return errorText(blocked);
        }
        return null;
    }

    /** ChatChunk.error 写的是 message 字段；这里兼容 content，避免把真实原因吞掉。 */
    private String errorText(Flux<ChatChunk> blocked) {
        ChatChunk chunk = blocked.blockFirst();
        if (chunk == null) return "消息校验未通过";
        if (chunk.getMessage() != null) return chunk.getMessage();
        return chunk.getContent() != null ? chunk.getContent() : "消息校验未通过";
    }

    /**
     * 附加字段校验 — 只做长度与注入检测，不占用限频/每日额度。
     * 用于 message 之外的前端可控字段：history / sceneContext / practiceAngles / conversation。
     *
     * @param fieldName      字段中文名，用于日志与提示
     * @param value          字段值
     * @param maxChars       最大长度
     * @param checkInjection 是否做注入正则（纯历史文本建议 false，避免误杀）
     * @return null = 放行；非 null = 用户可见错误文案
     */
    @Nullable
    public String auditAuxiliaryField(String fieldName, @Nullable String value,
                                      int maxChars, boolean checkInjection) {
        if (!isEnabled() || value == null || value.isBlank()) {
            return null;
        }

        if (value.length() > maxChars) {
            log.info("QueryGate aux: {} 超长被拦截 length={} limit={}", fieldName, value.length(), maxChars);
            blockCounter("1_basic", "aux_too_long").increment();
            return fieldName + "内容过长，请精简后重试";
        }

        if (checkInjection) {
            for (Pattern p : promptInjectionPatterns) {
                if (p.matcher(value).find()) {
                    log.warn("QueryGate aux: {} 命中注入检测 pattern={}", fieldName, p.pattern());
                    blockCounter("2_content", "aux_prompt_injection").increment();
                    return "抱歉，我无法处理该请求";
                }
            }
        }
        return null;
    }

    /** 把一段用户可见文案包装成「错误 + 结束」的流，供 Controller 短路返回。 */
    public Flux<ChatChunk> blocked(String message) {
        return Flux.just(ChatChunk.error(message), ChatChunk.done());
    }

    /**
     * 仅限频（L3）— 用于没有单一 message 字段、但会调用 LLM 的入口：
     * 对练评分、对练总结评价。避免这些入口绕过限频。
     */
    @Nullable
    public Flux<ChatChunk> auditRateOnly(@Nullable UUID userId, @Nullable String role, String clientIp) {
        if (!isEnabled()) {
            return null;
        }
        return auditRate(userId, role, clientIp);
    }

    // ============================================================
    // Layer 1: 基础合法性校验
    // ============================================================

    /**
     * 基础合法性 — 空消息、纯符号、超长文本。
     *
     * <p>仅使用 Java 正则和 String 操作，零外部 I/O，0ms 延迟。
     * 被拦截的消息不会记录到数据库，也不会送到 LLM。</p>
     */
    @Nullable
    private Flux<ChatChunk> auditBasic(@Nullable String message) {
        // 空消息 — @NotBlank 的二次兜底（Controller 可能绕过验证）
        if (message == null || message.isBlank()) {
            log.debug("QueryGate L1: 空消息被拦截");
            blockCounter("1_basic", "empty").increment();
            return Flux.just(ChatChunk.error("请输入有效问题"), ChatChunk.done());
        }

        // 纯符号/emoji — 去除空白后全是标点、特殊符号即拦截
        String stripped = message.replaceAll("\\s+", "");
        if (PURE_SYMBOL.matcher(stripped).matches()) {
            log.debug("QueryGate L1: 纯符号被拦截 length={}", message.length());
            blockCounter("1_basic", "pure_symbol").increment();
            return Flux.just(ChatChunk.error("请输入有效问题"), ChatChunk.done());
        }

        // 超长文本 — 防止 DOS 攻击（超大消息直达 AI 提供商）
        if (message.length() > config.getMaxMessageLength()) {
            log.info("QueryGate L1: 超长消息被拦截 length={} limit={}", message.length(), config.getMaxMessageLength());
            blockCounter("1_basic", "too_long").increment();
            return Flux.just(ChatChunk.error("消息过长，请精简后发送"), ChatChunk.done());
        }

        return null;
    }

    // ============================================================
    // Layer 2: 内容安全过滤
    // ============================================================

    /**
     * 内容安全 — 违禁词 Trie 匹配 + 隐私泄露检测 + Prompt 注入防护。
     *
     * <p>所有匹配在内存中完成，0-1ms 延迟。违禁词通过 Redis Pub/Sub + 定时轮询热更新。
     * ReDoS 防护：正则中 .{0,N} 限制回溯深度，从语法层面防止灾难性回溯。</p>
     */
    @Nullable
    private Flux<ChatChunk> auditContent(String message) {
        // ── 违禁词 Trie 匹配（词表插入时已小写，匹配也必须小写，否则大小写可绕过） ──
        TrieNode trie = this.bannedWordsTrie;  // volatile 读，获取最新快照
        String lowered = message.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < lowered.length(); i++) {
            TrieNode node = trie;
            for (int j = i; j < lowered.length(); j++) {
                node = node.children.get(lowered.charAt(j));
                if (node == null) break;
                if (node.isEnd) {
                    // 命中违禁词 — 静默拦截，不告诉攻击者具体原因
                    log.warn("QueryGate L2: 违禁词命中，已拦截 length={}", message.length());
                    blockCounter("2_content", "banned_word").increment();
                    return Flux.just(ChatChunk.error("抱歉，我无法回答这个问题"), ChatChunk.done());
                }
            }
        }

        // ── 隐私泄露检测：手机号/身份证号（告知用户，非静默） ──
        if (phonePattern.matcher(message).find()) {
            log.info("QueryGate L2: 检测到手机号被拦截");
            blockCounter("2_content", "privacy_phone").increment();
            return Flux.just(ChatChunk.error("检测到手机号，为保护隐私已自动拦截。请勿发送个人敏感信息。"), ChatChunk.done());
        }
        if (idCardPattern.matcher(message).find()) {
            log.info("QueryGate L2: 检测到身份证号被拦截");
            blockCounter("2_content", "privacy_idcard").increment();
            return Flux.just(ChatChunk.error("检测到身份证号，为保护隐私已自动拦截。请勿发送个人敏感信息。"), ChatChunk.done());
        }

        // ── Prompt 注入检测（静默拦截） ──
        // .{0,N} 限制回溯深度，已在正则编译时防止 ReDoS，此处直接匹配
        for (Pattern p : promptInjectionPatterns) {
            if (p.matcher(message).find()) {
                log.warn("QueryGate L2: Prompt注入检测命中 pattern={}", p.pattern());
                blockCounter("2_content", "prompt_injection").increment();
                return Flux.just(ChatChunk.error("抱歉，我无法回答这个问题"), ChatChunk.done());
            }
        }

        return null;
    }

    // ============================================================
    // Layer 3: 频率控制
    // ============================================================

    /**
     * 频率控制 — IP 级 + 用户级双层 Redis 固定窗口。
     *
     * <p>userId 或 role 为 null 时仅做 IP 限流（IM 回调场景）。
     * Redis 异常时 fail-open 放行。</p>
     */
    @Nullable
    private Flux<ChatChunk> auditRate(@Nullable UUID userId, @Nullable String role, String clientIp) {
        // ── IP 级限流（全局兜底，防 DDoS） ──
        int ipLimit = config.getRateLimit().getIpPerMin();
        if (ipLimit > 0 && !allow("rate:ip", clientIp, ipLimit, Duration.ofMinutes(1))) {
            log.warn("QueryGate L3: IP限流触发 ip={}", clientIp);
            blockCounter("3_rate", "ip").increment();
            return Flux.just(ChatChunk.error("发送太频繁，请稍后再试"), ChatChunk.done());
        }

        // ── 用户级限流（需要 userId + role） ──
        if (userId == null || role == null) return null;

        int userLimit = resolveUserRateLimit(role);
        if (userLimit <= 0) return null;  // -1 表示不限频

        String rateKey = "rate:user:" + role + ":" + userId + ":" + LocalDateTime.now().format(MINUTE_FMT);
        if (!allow(rateKey, "", userLimit, Duration.ofMinutes(1))) {
            int remainingSec = 60 - LocalDateTime.now().getSecond();
            log.warn("QueryGate L3: 用户限流触发 userId={} role={}", userId, role);
            blockCounter("3_rate", "user").increment();
            return Flux.just(
                ChatChunk.error("发送太快，请稍等 " + remainingSec + " 秒"),
                ChatChunk.done());
        }

        return null;
    }

    /** 根据角色解析每分钟限频数 */
    private int resolveUserRateLimit(String role) {
        return switch (role) {
            case "c_guest"    -> config.getRateLimit().getGuestPerMin();
            case "c_user"     -> config.getRateLimit().getUserPerMin();
            case "c_partner"  -> config.getRateLimit().getPartnerPerMin();
            case "employee",
                 "super_admin",
                 "company_admin" -> config.getRateLimit().getEmployeePerMin();
            // 未知角色不能落到 -1（不限频），按员工档保守处理
            default -> config.getRateLimit().getEmployeePerMin();
        };
    }

    // ============================================================
    // Layer 4: 游客额度
    // ============================================================

    /**
     * C 端每日免费额度 — c_guest 与 c_user 角色生效，其余角色（B 端）直接放行。
     *
     * <p>被拦截的消息不落库。游客与注册用户各自独立限额（可配置，后续订阅分级）。</p>
     */
    @Nullable
    private Flux<ChatChunk> auditQuota(@Nullable UUID userId, @Nullable String role) {
        if (userId == null) {
            return null;
        }

        final int limit;
        final String code;
        if ("c_guest".equals(role)) {
            limit = guestMessageLimit;
            code = "GUEST_LIMIT_REACHED";
        } else if ("c_user".equals(role)) {
            limit = cUserMessageLimit;
            code = "USER_LIMIT_REACHED";
        } else {
            // 员工 / 管理员等 B 端角色不受每日条数限制
            return null;
        }

        long used = skillMessageRepository.countUserMessagesByUserIdSince(
            userId, LocalDate.now().atStartOfDay());
        if (used >= limit) {
            log.info("QueryGate L4: 免费额度已用完 userId={} role={} used={}/{}", userId, role, used, limit);
            blockCounter("4_quota", "c_guest".equals(role) ? "guest_limit" : "user_limit").increment();
            return Flux.just(
                ChatChunk.event("limit", Map.of(
                    "code", code,
                    "used", used,
                    "limit", limit)),
                ChatChunk.done());
        }

        return null;
    }

    // ============================================================
    // Redis 工具方法（fail-open）
    // ============================================================

    /**
     * Redis INCR + TTL 固定窗口限流。
     *
     * <p>key 和 prefix 拼接为完整 Redis key。首次 INCR 返回 1 时设置 TTL。
     * Redis 异常时返回 true（fail-open — 限流是兜底而非主控制）。</p>
     */
    private boolean allow(String prefix, String suffix, int limit, Duration ttl) {
        String key = prefix + ":" + suffix;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, ttl);
            }
            return count == null || count <= limit;
        } catch (Exception e) {
            log.warn("QueryGate Redis 异常，fail-open 放行 key={}: {}", key, e.getMessage());
            return true;
        }
    }

    // ============================================================
    // 违禁词 Trie 管理
    // ============================================================

    /** 从 Redis Set 重新加载违禁词词表到本地 Trie。Redis 不可用时保留上次快照。 */
    private synchronized void reloadTrieFromRedis() {
        try {
            Set<String> words = redisTemplate.opsForSet()
                .members(config.getBannedWordsRedisKey());
            if (words == null || words.isEmpty()) {
                log.debug("QueryGate 违禁词词表为空（Redis key={}），使用空 Trie", config.getBannedWordsRedisKey());
                this.bannedWordsTrie = new TrieNode();
                return;
            }

            TrieNode root = new TrieNode();
            for (String word : words) {
                if (word == null || word.isBlank()) continue;
                insertTrie(root, word.trim().toLowerCase(java.util.Locale.ROOT));
            }
            this.bannedWordsTrie = root;
            log.info("QueryGate 违禁词词表已加载 count={}", words.size());
        } catch (Exception e) {
            log.warn("QueryGate 从 Redis 加载违禁词失败，保留上次快照: {}", e.getMessage());
        }
    }

    /** 将单词插入 Trie 树 */
    private void insertTrie(TrieNode root, String word) {
        TrieNode node = root;
        for (int i = 0; i < word.length(); i++) {
            node = node.children.computeIfAbsent(word.charAt(i), k -> new TrieNode());
        }
        node.isEnd = true;
    }

    // ============================================================
    // 监控辅助
    // ============================================================

    private Counter blockCounter(String layer, String reason) {
        if (meterRegistry == null) {
            return NoopCounter.INSTANCE;
        }
        return blockCounters.computeIfAbsent(layer + ":" + reason, k ->
            Counter.builder("query_gate_blocks_total")
                .description("QueryGate 拦截的消息数")
                .tag("layer", layer)
                .tag("reason", reason)
                .register(meterRegistry));
    }

    /** 未引入 actuator 时的空计数器 — 拦截路径绝不能因为指标缺失而抛异常。 */
    private enum NoopCounter implements Counter {
        INSTANCE;

        @Override
        public void increment(double amount) {
            // no-op
        }

        @Override
        public double count() {
            return 0d;
        }

        @Override
        public Meter.Id getId() {
            return new Meter.Id("query_gate_noop", Tags.empty(), null, null, Meter.Type.COUNTER);
        }
    }

    // ============================================================
    // Trie 节点
    // ============================================================

    /** Trie 树节点 — 用于违禁词多模式匹配。每个节点存储一个字符的子节点映射。 */
    private static class TrieNode {
        final Map<Character, TrieNode> children = new ConcurrentHashMap<>();
        boolean isEnd;
    }
}
