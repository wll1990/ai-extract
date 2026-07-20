package com.aiextract.common;

import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 全链路追踪上下文（基于 SLF4J MDC）
 *
 * TraceId 格式: tr-{MMddHHmmss}-{skillId前8位}-{4位随机hex}
 *
 * 核心能力：
 * 1. MDC.put("traceId", ...) → 每条日志自动携带 traceId，无需手动拼写
 * 2. wrap() → 跨线程传播 traceId，解决 CompletableFuture 子线程丢失 MDC 的问题
 * 3. 响应头 X-Trace-Id → 前端可获取，出问题时关联前后端日志
 */
public class TraceContext {

    public static final String MDC_KEY = "traceId";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MMddHHmmss");
    private static final ThreadLocal<Long> START_TIME = new ThreadLocal<>();

    /** 生成 traceId，写入 MDC */
    public static String init(UUID skillId) {
        String ts = LocalDateTime.now().format(TS);
        String skillPrefix = skillId.toString().replace("-", "").substring(0, 8);
        String rand = Integer.toHexString((int) (System.nanoTime() & 0xFFFF));
        String traceId = String.format("tr-%s-%s-%s", ts, skillPrefix, rand);
        MDC.put(MDC_KEY, traceId);
        START_TIME.set(System.currentTimeMillis());
        return traceId;
    }

    /** 获取当前 traceId */
    public static String get() {
        String id = MDC.get(MDC_KEY);
        if (id == null) {
            id = "tr-" + LocalDateTime.now().format(TS) + "-00000000-" +
                 Integer.toHexString((int) (System.nanoTime() & 0xFFFF));
        }
        return id;
    }

    /** 从请求开始到现在的耗时（毫秒） */
    public static long elapsed() {
        Long start = START_TIME.get();
        return start != null ? System.currentTimeMillis() - start : 0;
    }

    /** 清理 MDC 和 ThreadLocal */
    public static void clear() {
        MDC.remove(MDC_KEY);
        START_TIME.remove();
    }

    // ========================================
    // 跨线程传播（解决 CompletableFuture 子线程丢失 MDC）
    // ========================================

    /** 包装 Runnable，传播 MDC 到子线程 */
    public static Runnable wrap(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {

                MDC.setContextMap(context);

            }
            else MDC.clear();
            try { task.run(); } finally { MDC.clear(); }
        };
    }

    /** 包装 Supplier，传播 MDC 到 CompletableFuture 子线程 */
    public static <T> Supplier<T> wrap(Supplier<T> task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            if (context != null) {

                MDC.setContextMap(context);

            }
            else MDC.clear();
            try { return task.get(); } finally { MDC.clear(); }
        };
    }

    /**
     * 包装 CompletableFuture — 自动传播 MDC，使用指定线程池
     *
     * <p>必须显式传入 Executor，避免使用公共 ForkJoinPool。
     * 推荐使用 Spring 管理的线程池（embeddingExecutor / parseExecutor / cleanExecutor）。</p>
     */
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, Executor executor) {
        return CompletableFuture.supplyAsync(wrap(supplier), executor);
    }

    /**
     * @deprecated 使用 {@link #supplyAsync(Supplier, Executor)} 并显式传入线程池。
     *             无参版使用 ForkJoinPool.commonPool()，不适用于业务异步场景。
     */
    @Deprecated
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(wrap(supplier));
    }
}
