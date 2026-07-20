package com.aiextract.model;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AI分身素材实体类
 *
 * <p>对应 skill_material 表，管理AI分身训练素材的完整生命周期，
 * 包括上传、清洗、解析和知识提取共11层处理流水线，使用分布式锁保证并发安全。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "skill_material")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillMaterial {

    /**
     * 素材唯一标识
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * 所属AI分身ID
     */
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    /**
     * 上传人ID
     */
    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    /**
     * 文件名
     */
    @Column(name = "file_name", nullable = false, length = 500)
    private String fileName;

    /**
     * 文件存储地址
     */
    @Column(name = "file_url", length = 500)
    private String fileUrl;

    /**
     * 文件类型
     */
    @Column(name = "file_type", length = 200)
    private String fileType;

    /**
     * 素材类型: dialogue=对话, monologue=独白/心得, interview=访谈
     */
    @Column(name = "material_type", length = 20)
    private String materialType;

    /**
     * 文件大小（字节）
     */
    @Column(name = "file_size")
    private Long fileSize;

    /**
     * 解析后的文本内容
     */
    @Column(name = "parsed_content", columnDefinition = "TEXT")
    private String parsedContent;

    /**
     * 版本号（默认1）
     */
    @Column(name = "version")
    @Builder.Default
    private Integer version = 1;

    /**
     * 替代的旧素材ID（版本更新时使用）
     */
    @Column(name = "replaces_material_id")
    private UUID replacesMaterialId;

    /**
     * 素材状态：uploaded=已上传 / cleaning=清洗中 / analyzing=分析中 / analyzed=已分析 / extracted=已提取 / rejected=已拒绝 / failed=访谈清洗失败 / discarded=已废弃
     */
    @Column(length = 20)
    @Builder.Default
    private String status = "uploaded";

    /**
     * 解析/清洗失败重试次数，上限 3 次
     */
    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * 分析备注
     */
    @Column(name = "analysis_notes", columnDefinition = "TEXT")
    private String analysisNotes;

    /**
     * 提取元数据（JSON格式）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extraction_metadata", columnDefinition = "TEXT")
    private String extractionMetadata;

    /**
     * 创建时间（默认为当前时间）
     */
    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * 分布式锁持有者标识
     */
    @Column(name = "locked_by", length = 64)
    private String lockedBy;

    /**
     * 分布式锁获取时间
     */
    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    /**
     * 更新前自动设置更新时间
     */
    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
