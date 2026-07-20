package com.aiextract.service.precheck;

import java.util.List;

/**
 * 预检结果 — 由 MaterialPreChecker.run() 返回。
 *
 * @param overallScore      综合评分 0-100
 * @param grade             等级: good(≥70) / warning(50-69) / poor(<50)
 * @param estimatedGrainMin 预估颗粒下限
 * @param estimatedGrainMax 预估颗粒上限
 * @param detectedScenes    检测到的潜在场景标签
 * @param checks            逐项检查结果
 */
public record PreCheckResult(
    int overallScore,
    String grade,
    int estimatedGrainMin,
    int estimatedGrainMax,
    List<String> detectedScenes,
    List<CheckItem> checks
) {}
