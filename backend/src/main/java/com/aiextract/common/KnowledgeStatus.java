package com.aiextract.common;

import java.time.LocalDateTime;

/**
 * 知识/案例有效状态派生工具 —— 「过期」不落库，由失效时间派生。
 *
 * <p>状态只存 draft/pending/active/deprecated；当 status=active 且 expire_at 早于当前时间时，
 * 派生显示态 {@link #EXPIRED}。列表 UI 与检索 SQL 均基于此规则判断。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
public final class KnowledgeStatus {

    /** 派生显示态：已过期 */
    public static final String EXPIRED = "expired";

    private KnowledgeStatus() {
    }

    /**
     * 派生有效显示状态。
     *
     * @param status   落库状态（draft/pending/active/deprecated）
     * @param expireAt 失效时间（可空）
     * @param now      当前时间
     * @return active 且已过失效时间 → expired，否则原状态
     */
    public static String deriveEffectiveStatus(String status, LocalDateTime expireAt, LocalDateTime now) {
        if ("active".equals(status) && expireAt != null && expireAt.isBefore(now)) {
            return EXPIRED;
        }
        return status;
    }
}
