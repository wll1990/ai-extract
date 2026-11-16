package com.aiextract.controller;

import com.aiextract.common.ApiResponse;
import com.aiextract.service.TrainingEffectService;
import com.aiextract.service.TrainingNeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * AI Native 培训的「需求 → 生成 → 效果」视图。
 *
 * <p>数据全部从真实业务信号实时聚合，不落冗余表；只读接口，
 * 由 SecurityConfig 的 TRAINING_MANAGE 保护。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-15
 */
@RestController
@RequestMapping("/admin/training")
@RequiredArgsConstructor
public class AdminTrainingInsightController {

    private final TrainingNeedService needService;
    private final TrainingEffectService effectService;

    /** 能力缺口：知识缺口 + 实战失败按场景聚合，供「AI 生成计划」使用 */
    @GetMapping("/needs")
    public ApiResponse<TrainingNeedService.NeedsView> needs(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(needService.needs(limit));
    }

    /** 批次效果：认证率、补训轮次、AI 转人工率、场景均分、待补缺口 */
    @GetMapping("/cohorts/{id}/effect")
    public ApiResponse<TrainingEffectService.EffectView> effect(@PathVariable UUID id) {
        return ApiResponse.success(effectService.effect(id));
    }
}
