package com.aiextract.config;

import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.Nullable;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Token 统计上下文 — ThreadLocal 传递 userId 和 companyId，不侵入业务代码。
 *
 * <p>Filter 层自动设置，ChatModel 包装器自动读取，请求结束自动清理。
 * TaskDecorator 确保 @Async 线程池复用时不丢失上下文。</p>
 *
 * <p><b>企业视角</b>：HOME 是登录 JWT 自带的企业；ACTING 是超管主动切换查看的企业。
 * {@link #getCompanyId()} 返回生效企业（acting 优先），数据范围用它；
 * 平台特权/审计用 {@link #getHomeCompanyId()} 和 {@link #getActingCompanyId()} 区分。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
public final class TokenContext {

    private static final ThreadLocal<UUID> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> COMPANY_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> ACTING_COMPANY_ID = new ThreadLocal<>();

    private TokenContext() {}

    public static void set(@Nullable UUID userId) {
        USER_ID.set(userId);
    }

    /** 设置用户与企业；调用方没有 acting 语义时明确清空，避免线程复用时串视角 */
    public static void set(@Nullable UUID userId, @Nullable UUID companyId) {
        USER_ID.set(userId);
        COMPANY_ID.set(companyId);
        ACTING_COMPANY_ID.remove();
    }

    /** 设置用户、登录企业与当前查看视角 */
    public static void set(@Nullable UUID userId, @Nullable UUID companyId,
                           @Nullable UUID actingCompanyId) {
        USER_ID.set(userId);
        COMPANY_ID.set(companyId);
        if (actingCompanyId == null) {
            ACTING_COMPANY_ID.remove();
        } else {
            ACTING_COMPANY_ID.set(actingCompanyId);
        }
    }

    @Nullable
    public static UUID get() {
        return USER_ID.get();
    }

    /** 生效企业：切换视角优先，否则登录企业 */
    @Nullable
    public static UUID getCompanyId() {
        UUID acting = ACTING_COMPANY_ID.get();
        return acting != null ? acting : COMPANY_ID.get();
    }

    /** 登录 JWT 自带的企业（未切换视角时的归属） */
    @Nullable
    public static UUID getHomeCompanyId() {
        return COMPANY_ID.get();
    }

    /** 超管当前查看的企业；null 表示平台视角（全部企业） */
    @Nullable
    public static UUID getActingCompanyId() {
        return ACTING_COMPANY_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
        COMPANY_ID.remove();
        ACTING_COMPANY_ID.remove();
    }

    /** 在指定用户/企业上下文内执行，结束后恢复原值（异步任务用） */
    public static <T> T callWith(@Nullable UUID userId, @Nullable UUID companyId, Supplier<T> action) {
        return callWith(userId, companyId, null, action);
    }

    /**
     * 在指定用户、登录企业与查看视角内执行，结束后恢复原值。
     *
     * <p>异步任务需要显式传入在请求线程捕获的 acting，不能依赖调用时的 ThreadLocal。</p>
     */
    public static <T> T callWith(@Nullable UUID userId, @Nullable UUID companyId,
                                 @Nullable UUID actingCompanyId, Supplier<T> action) {
        UUID oldUser = USER_ID.get();
        UUID oldCompany = COMPANY_ID.get();
        UUID oldActing = ACTING_COMPANY_ID.get();
        try {
            set(userId, companyId, actingCompanyId);
            return action.get();
        } finally {
            restore(USER_ID, oldUser);
            restore(COMPANY_ID, oldCompany);
            restore(ACTING_COMPANY_ID, oldActing);
        }
    }

    public static void callWith(@Nullable UUID userId, @Nullable UUID companyId, Runnable action) {
        callWith(userId, companyId, () -> {
            action.run();
            return null;
        });
    }

    private static void restore(ThreadLocal<UUID> holder, @Nullable UUID value) {
        if (value == null) {
            holder.remove();
        } else {
            holder.set(value);
        }
    }

    /** 异步线程池装饰器 — 把父线程的 userId、企业与企业视角传递给子线程 */
    public static class Decorator implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            UUID parentUserId = USER_ID.get();
            UUID parentCompanyId = COMPANY_ID.get();
            UUID parentActingCompanyId = ACTING_COMPANY_ID.get();
            return () -> {
                try {
                    set(parentUserId, parentCompanyId, parentActingCompanyId);
                    runnable.run();
                } finally {
                    clear();
                }
            };
        }
    }
}
