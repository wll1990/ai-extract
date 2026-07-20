package com.aiextract.model;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 萃取管道淘汰记录实体
 *
 * <p>对应 extraction_drop_log 表。记录清洗管道中被丢弃的候选内容明细，
 * 用于排查"这段经验为什么没出锦囊"：
 * <ul>
 *   <li>dedup — chunk 与存量颗粒相似度超阈值被去重</li>
 *   <li>verification — 对抗验证 AI 打分不达标被拒绝</li>
 *   <li>verification_skipped — 验证 AI 异常，整批 fail-open 放行（无淘汰但需留痕）</li>
 * </ul>
 *
 * <p>注意：本实体带 @GeneratedValue，builder 禁止手动预置 id（persist 时生成回填）。</p>
 *
 * @author AI Extract Team
 * @since 2026-07-19
 */
@Entity
@Table(name = "extraction_drop_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExtractionDropLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 本次萃取的素材 */
    @Column(name = "material_id", nullable = false)
    private UUID materialId;

    /** 所属空间（分身） */
    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    /** 淘汰阶段: dedup / verification / verification_skipped */
    @Column(length = 30, nullable = false)
    private String stage;

    /** dedup: 被丢 chunk 序号 */
    @Column(name = "chunk_index")
    private Integer chunkIndex;

    /** 被丢内容摘要 */
    @Column(name = "content_preview", columnDefinition = "TEXT")
    private String contentPreview;

    /** dedup: 撞上的存量颗粒 id */
    @Column(name = "collided_grain_id")
    private UUID collidedGrainId;

    /** dedup: Jaccard 相似度 */
    @Column(precision = 4, scale = 3)
    private BigDecimal similarity;

    /** verification: AI 打分; skipped: 批次信息（JSON） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSONB")
    private String detail;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
