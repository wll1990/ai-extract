package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * IM渠道响应DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImChannelResponse {

    /** 渠道ID */
    private String id;

    /** 渠道类型 */
    private String channelType;

    /** 是否启用 */
    private Boolean enabled;

    /** 渠道配置 */
    private Object config;

    /** 关联Skill ID列表 */
    private List<String> linkedSkills;

    /** 创建时间 */
    private String createdAt;
}
