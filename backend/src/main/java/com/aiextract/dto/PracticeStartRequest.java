package com.aiextract.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 开始对练请求DTO
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PracticeStartRequest {

    /**
     * 预设场景标签（可选）
     */
    private String scene;

    /**
     * 自定义场景描述（可选，优先级高于scene）
     */
    private String customScene;
}
