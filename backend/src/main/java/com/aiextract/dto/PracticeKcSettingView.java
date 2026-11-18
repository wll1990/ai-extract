package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 对练知识/案例检索开关。
 *
 * @param enabled        本企业当前是否开启
 * @param defaultEnabled 未配置时的全局默认值（运维可见，解释「为什么现在还是关的」）
 * @param updatedBy      最近一次修改人
 * @param updatedAt      最近一次修改时间
 */
public record PracticeKcSettingView(boolean enabled, boolean defaultEnabled,
                                    UUID updatedBy, LocalDateTime updatedAt) {
}
