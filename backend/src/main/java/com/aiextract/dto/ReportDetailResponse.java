package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 报告详情响应DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportDetailResponse {

    /** 报告ID */
    private String id;

    /** 空间ID */
    private String spaceId;

    /** 报告标题 */
    private String title;

    /** 副标题 */
    private String subtitle;

    /** 报告内容（JSON） */
    private Object contentJson;

    /** Word下载链接 */
    private String wordUrl;

    /** PPT下载链接 */
    private String pptUrl;

    /** Web版是否已发布 */
    private Boolean webPublished;

    /** 文件同步状态 */
    private String fileStatus;

    /** 评分 */
    private BigDecimal rating;

    /** 浏览次数 */
    private Integer viewCount;

    /** 作者名称 */
    private String authorName;

    /** 关联的分身ID */
    private String skillId;

    /** 分身状态 */
    private String skillStatus;

    /** 作者头像 */
    private String authorAvatar;

    /** 创建时间 */
    private String createdAt;

    /** 更新时间 */
    private String updatedAt;
}
