package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 颗粒编辑请求 */
/**
 * @author AI Extract Team
 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ExpertGrainEditRequest {
    private String category;
    private String sceneDescription;
    private String knowledgeContent;
    private String applicationRule;
    private Integer priority;
    private String status;
}
