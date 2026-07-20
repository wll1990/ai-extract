package com.aiextract.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 创建访谈请求DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateInterviewRequest {

    /**
     * 所属空间ID（必填）
     */
    @NotBlank(message = "空间ID不能为空")
    private String spaceId;

    /**
     * 萃取主题（必填）
     */
    @NotBlank(message = "萃取主题不能为空")
    private String topic;

    /**
     * 邀请码（可选）
     */
    private String inviteCode;

    /**
     * 萃取师Skill ID（可选，不传=综合，"none"=基础版）
     */
    private String expertSkillId;

    /**
     * 访谈类型：sales（销冠）/ expert（萃取师）
     */
    private String interviewType;
}
