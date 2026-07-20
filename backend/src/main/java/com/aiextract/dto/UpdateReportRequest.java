package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 编辑报告请求DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateReportRequest {

    /** 章节列表（包含修改后的内容） */
    private List<ChapterUpdate> chapters;

    /** 是否重新生成Word/PPT */
    private Boolean regenerate;

    /**
     * 章节更新内部类
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChapterUpdate {

        /** 章节序号 */
        private Integer order;

        /** 章节内容 */
        private Object content;
    }
}
