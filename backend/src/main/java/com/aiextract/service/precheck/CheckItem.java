package com.aiextract.service.precheck;

/**
 * 单项检查结果。
 *
 * @param dimension  维度: structure / content / quality
 * @param name       检查项名称: dialogue_turns / objection_signals / ...
 * @param passed     是否通过
 * @param score      该项得分 0-100
 * @param feedback   人类可读的检查结果
 * @param suggestion 改进建议（未通过时提供）
 */
public record CheckItem(
    String dimension,
    String name,
    boolean passed,
    int score,
    String feedback,
    String suggestion
) {}
