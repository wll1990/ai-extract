package com.aiextract.config;

import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.Nullable;

import java.util.UUID;

/**
 * Token 统计上下文 — ThreadLocal 传递 userId 和 companyId，不侵入业务代码。
 *
 * <p>Filter 层自动设置，ChatModel 包装器自动读取，请求结束自动清理。
 * TaskDecorator 确保 @Async 线程池复用时不丢失上下文。
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
public final class TokenContext {

    private static final ThreadLocal<UUID> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> COMPANY_ID = new ThreadLocal<>();

    private TokenContext() {}

    public static void set(@Nullable UUID userId) {
        USER_ID.set(userId);
    }

    public static void set(@Nullable UUID userId, @Nullable UUID companyId) {
        USER_ID.set(userId);
        COMPANY_ID.set(companyId);
    }

    @Nullable
    public static UUID get() {
        return USER_ID.get();
    }

    @Nullable
    public static UUID getCompanyId() {
        return COMPANY_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
        COMPANY_ID.remove();
    }

    /** 异步线程池装饰器 — 把父线程的 userId 和 companyId 传递给子线程 */
    public static class Decorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            UUID parentUserId = USER_ID.get();
            UUID parentCompanyId = COMPANY_ID.get();
            return () -> {
                try {
                    USER_ID.set(parentUserId);
                    COMPANY_ID.set(parentCompanyId);
                    runnable.run();
                } finally {
                    USER_ID.remove();
                    COMPANY_ID.remove();
                }
            };
        }
    }
}
