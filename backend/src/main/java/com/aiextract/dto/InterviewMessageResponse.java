package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 访谈消息响应DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewMessageResponse {

    /**
     * 消息ID
     */
    private String id;

    /**
     * 消息角色：ai / user / system
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 追问深度
     */
    private Integer depth;

    /**
     * 所属阶段
     */
    private String phase;

    /**
     * 创建时间
     */
    private String createdAt;
}
