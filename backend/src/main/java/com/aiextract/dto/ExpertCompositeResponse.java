package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 综合Skill详情 */
/**
 * @author AI Extract Team
 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ExpertCompositeResponse {
    private String version;
    private int expertCount;
    private int consensusCount;
    private int singleCount;
    private int conflictCount;
    private String updatedAt;
    private String contentPreview;
}
