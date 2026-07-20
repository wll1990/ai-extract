package com.aiextract.common;

/**
 * 状态常量
 *
 * @since 2026-07-01
 * @author AI Extract Team
 */
public final class StatusConstants {

    private StatusConstants() {}

    /** 文件同步状态 */
    public static final String FILE_SYNCED = "synced";
    public static final String FILE_PENDING_REGENERATE = "pending_regenerate";

    /** 萃取师状态 */
    public static final String EXPERT_ACTIVE = "active";
    public static final String EXPERT_UNDER_REVIEW = "under_review";
    public static final String EXPERT_PENDING = "pending";
    public static final String EXPERT_EXTRACTING = "extracting";
    public static final String EXPERT_FAILED = "failed";

    /** 9 大销售场景标签 */
    public static final java.util.List<String> SCENE_TAGS = java.util.List.of(
            "破冰", "异议处理", "逼单", "竞品博弈", "信任建立",
            "决策链渗透", "价格谈判", "转介绍", "高层拜访");
}
