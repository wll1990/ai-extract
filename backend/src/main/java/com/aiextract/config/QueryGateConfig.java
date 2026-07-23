package com.aiextract.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Query Gate 配置 — 绑定 {@code app.query-gate.*}。
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>可剥离</b>：删除此文件不影响任何其他模块</li>
 *   <li><b>可运行时开关</b>：Redis key {@code query_gate:enabled} 优先级高于 YAML，
 *       运维秒级操作无需重启</li>
 * </ul>
 *
 * @since 2026-07-23
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.query-gate")
public class QueryGateConfig {

    /**
     * 总开关 — 启动默认值。
     * 运行时可通过 Redis key "query_gate:enabled" 覆盖（优先级更高）。
     * false 时所有规则停用，零性能损耗。
     */
    private boolean enabled = true;

    /**
     * 单条消息最大字符数（约 8000 个中文字或 5300 个英文词）。
     * 超过此长度返回"消息过长"，不进入 LLM。
     */
    private int maxMessageLength = 8000;

    /**
     * 各角色分钟级频率限制。
     * 值为 -1 表示不限频。
     */
    private RateLimit rateLimit = new RateLimit();

    /**
     * Redis 中违禁词 Set 的 key 名。
     * 运维可通过 redis-cli 热更新：SADD query_gate:banned_words "违规词"
     * 更新后 PUBLISH query_gate:banned_words:update "1" 触发即时刷新。
     */
    private String bannedWordsRedisKey = "query_gate:banned_words";

    /**
     * 敏感模式正则 — 编译时预编译为 Pattern，运行时 O(1) 匹配。
     * 每个 pattern 必须注释拦截目标和误伤风险评估。
     */
    private SensitivePatterns sensitivePatterns = new SensitivePatterns();

    @Data
    public static class RateLimit {
        /** 单 IP 全局上限 — 兜底防 DDoS */
        private int ipPerMin = 60;
        /** C 端游客限频 — 低于注册用户，防止未登录刷接口 */
        private int guestPerMin = 6;
        /** C 端注册用户限频 */
        private int userPerMin = 20;
        /** B 端员工限频 */
        private int employeePerMin = 30;
        /** IM 回调不限频（飞书/钉钉自有反滥用机制） */
        private int bEndImPerMin = -1;
    }

    @Data
    public static class SensitivePatterns {
        /** 中国大陆手机号 — 误伤风险极低 */
        private String phone = "\\b1[3-9]\\d{9}\\b";
        /** 18 位身份证号 — 误伤风险极低 */
        private String idCard = "\\b\\d{17}[\\dXx]\\b";
        /**
         * Prompt 注入攻击 — 覆盖中英文常见攻击模式。
         * 每项中 .{0,N} 限制回溯深度，防止 ReDoS。
         */
        private List<String> promptInjection = List.of(
            "忽略.{0,50}(指令|上文|提示|规则|设定|限制)",    // 中文：忽略上述指令等
            "(ignore|forget|override).{0,30}(instruction|prompt|rule)",  // 英文常见攻击
            "(system|user|assistant).{0,20}(prompt|message)",  // 角色伪装
            "从现在开始.{0,30}(角色|身份)"                    // 中文角色越狱
        );
    }
}
