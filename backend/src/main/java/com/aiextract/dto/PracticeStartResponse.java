package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 开始对练响应DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticeStartResponse {

    /**
     * 对练会话ID
     */
    private String practiceId;

    /**
     * 对话记录ID（用于历史持久化，未发布分身时为 null）
     */
    private String conversationId;

    /**
     * 场景信息
     */
    private PracticeSceneInfo scene;

    /**
     * 练习角度列表（客户会从这些角度提问）
     */
    private java.util.List<String> practiceAngles;

    /**
     * 练习角度总数
     */
    private int totalAngles;

    /**
     * 场景信息内部类
     */
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PracticeSceneInfo {

        /** 场景标题 */
        private String title;

        /** 场景设定 */
        private String setting;

        /** 客户首句台词 */
        private String customerLine;
    }
}
