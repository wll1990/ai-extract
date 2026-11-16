package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 已发布 IM 机器人（面向管理后台列表）。
 *
 * @author AI Extract Team
 * @since 2026-08-15
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImBotResponse {

    /** 渠道 ID（im_channel.id） */
    private String id;

    /** 渠道类型 */
    private String channelType;

    /** 平台 bot 标识 */
    private String botId;

    /** 平台应用 ID */
    private String appId;

    /** 绑定的分身 ID */
    private String skillId;

    /** 分身名称 */
    private String skillName;

    /** 生命周期状态：published / disabled */
    private String status;

    /** 是否启用 */
    private Boolean enabled;

    /** 连接状态：online / offline / reconnecting */
    private String connected;

    /** 最近一次发布/重连失败原因（status=failed 时展示） */
    private String lastError;

    /** 创建时间 */
    private String createdAt;
}
