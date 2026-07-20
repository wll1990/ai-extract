package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.List;

/** 萃取师详情 */
/**
 * @author AI Extract Team
 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ExpertSkillDetailResponse {
    private String id;
    private String name;
    private String description;
    private String sourceType;
    private List<String> styleTags;
    private List<String> industryTags;
    private String seniority;
    private String skillFile;
    private Integer grainCount;
    private String status;
    private List<ExpertGrainGroup> grainGroups;
    private List<ExpertDocumentInfo> documents;
    private String createdAt;
    private String updatedAt;

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ExpertGrainGroup {
        private String category;
        private List<ExpertGrainInfo> grains;
    }
    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ExpertGrainInfo {
        private String id;
        private String category;
        private String sourceType;
        private String sceneDescription;
        private String knowledgeContent;
        private String applicationRule;
        private Integer priority;
        private String consensusType;
        private String status;
    }
    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ExpertDocumentInfo {
        private String id;
        private String fileName;
        private String fileUrl;
        private String fileType;
        private Long fileSize;
        private String status;
    }
}
