package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.List;

/** 萃取师列表项 */
/**
 * @author AI Extract Team
 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ExpertSkillListResponse {
    private String id;
    private String name;
    private String description;
    private String sourceType;
    private String domain;
    private List<String> styleTags;
    private List<String> industryTags;
    private String seniority;
    private Integer grainCount;
    private Integer documentCount;
    private String status;
    /** 自动分析失败重试次数（上限 3） */
    private Integer retryCount;
    /** 最近一次失败原因 */
    private String failureReason;
    private String createdAt;
}
