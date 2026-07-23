package com.aiextract.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * TODO: 数据清理策略
 * 1. 按天聚合到 token_usage_daily 汇总表（user_id + usage_date + model_type GROUP BY）
 * 2. 原始明细保留 90 天，定时任务清理：DELETE FROM token_usage_log WHERE created_at < NOW() - INTERVAL '90 days'
 * 3. 或启用 TimescaleDB / 分区表按月自动归档
 */
@Entity
@Table(name = "token_usage_log", indexes = {
    @Index(name = "idx_token_user_date", columnList = "userId,usageDate"),
    @Index(name = "idx_token_date_model", columnList = "usageDate,modelType")
})
@Data @Builder
@NoArgsConstructor @AllArgsConstructor
public class TokenUsageLog {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    /** CHAT / EMBEDDING */
    @Column(name = "model_type", nullable = false, length = 20)
    private String modelType;

    /** deepseek-chat / text-embedding-v4 */
    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "input_tokens")
    private int inputTokens;

    @Column(name = "output_tokens")
    private int outputTokens;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
