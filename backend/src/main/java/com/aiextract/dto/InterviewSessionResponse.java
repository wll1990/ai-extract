package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * 访谈会话响应DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterviewSessionResponse {

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 萃取主题
     */
    private String topic;

    /**
     * 会话状态
     */
    private String status;

    /**
     * 当前阶段
     */
    private String currentPhase;

    /**
     * 使用的萃取师标识
     */
    private String expertSkillUsed;

    /**
     * 四阶段进度列表
     */
    private List<PhaseInfo> phases;

    /**
     * 报告模板模块预览
     */
    private TemplatePreview templatePreview;

    /**
     * 采集状态映射
     */
    private CollectStatus collectStatus;

    /**
     * 最后活跃时间
     */
    private String lastActiveAt;

    /**
     * 关联的报告ID（访谈完成后设置）
     */
    private String reportId;

    /**
     * 访谈类型（sales=销冠萃取, expert=萃取师访谈）
     */
    private String interviewType;

    /**
     * 阶段信息内部类
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhaseInfo {
        private String name;
        private String label;
        private String status;
    }

    /**
     * 模板预览内部类
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemplatePreview {
        private List<ModuleInfo> modules;
    }

    /**
     * 模块信息内部类
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModuleInfo {
        private String name;
        private Boolean collected;
    }

    /**
     * 采集状态内部类
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CollectStatus {
        private String caseStory;
        private String steps;
        private String decision;
        private String mindset;
        private String boundary;
        private String checklist;
    }
}
