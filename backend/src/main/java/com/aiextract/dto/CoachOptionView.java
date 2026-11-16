package com.aiextract.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 复盘教练选项：除名称外带经验丰富度（活跃颗粒数/场景数）和最近更新时间，
 * 供前端提示「经验较少，建议换一个教练或先补充素材」。
 */
public record CoachOptionView(UUID id, String name, String subtitle, String status,
                              long grainCount, long sceneCount, LocalDateTime updatedAt) {
}
