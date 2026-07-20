package com.aiextract.service.precheck;

import java.util.*;

/**
 * 素材准入检查结果。
 *
 * @param passed       是否通过准入
 * @param rejectCode   拒绝代码（如 NOT_SALES_DOMAIN、TOO_SHORT、DUPLICATE）
 * @param rejectReason 人类可读的拒绝原因
 * @param details      详细数据（如关键词密度值）
 */
public record AcceptanceResult(
    boolean passed,
    String rejectCode,
    String rejectReason,
    Map<String, Object> details
) {
    public static AcceptanceResult pass() {
        return new AcceptanceResult(true, null, null, Map.of());
    }

    public static AcceptanceResult reject(String code, String reason, Map<String, Object> details) {
        return new AcceptanceResult(false, code, reason, details);
    }
}
