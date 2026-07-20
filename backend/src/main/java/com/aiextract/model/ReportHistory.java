package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 报告生成历史实体类
 *
 * <p>对应 report_history 表，记录每次AI分身报告生成的版本快照，
 * 包括版本号、使用的素材和本次生成的颗粒数量。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "report_history")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportHistory {

    /**
     * 历史记录唯一标识
     */
    @Id
    private UUID id;

    /**
     * 所属AI分身ID
     */
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    /**
     * 版本号
     */
    @Column(name = "version", nullable = false, length = 50)
    private String version;

    /**
     * 生成时间
     */
    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    /**
     * 关联素材ID列表（JSON数组格式）
     */
    @Column(name = "material_ids", columnDefinition = "TEXT")
    private String materialIds;

    /**
     * 本次生成的颗粒数量
     */
    @Column(name = "grain_count")
    private Integer grainCount;

    /**
     * 生成元数据（JSON格式）
     */
    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;
}
