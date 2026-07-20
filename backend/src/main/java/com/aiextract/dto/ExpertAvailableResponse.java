package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/** 萃取师可用列表项 */
/**
 * @author AI Extract Team
 */
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ExpertAvailableResponse {
    private String id;
    private String name;
    private String type;
    private List<String> styleTags;
    private List<String> industryTags;
}
