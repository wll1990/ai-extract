package com.aiextract.model;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 报告实体类
 *
 * <p>对应 report 表，存储AI萃取生成的六章报告内容和下载文件链接。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "report")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Report {

    /**
     * 报告唯一标识
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 所属空间ID
     */
    @Column(name = "space_id")
    private UUID spaceId;

    /**
     * 关联的访谈会话ID
     */
    @Column(name = "session_id")
    private UUID sessionId;

    /**
     * 报告标题
     */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /**
     * 副标题
     */
    @Column(name = "subtitle", length = 500)
    private String subtitle;

    /**
     * 报告完整内容（JSON格式，六章结构）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", nullable = false, columnDefinition = "JSONB")
    private String contentJson;

    /**
     * Word文件下载链接
     */
    @Column(name = "word_url", length = 500)
    private String wordUrl;

    /**
     * PPT文件下载链接
     */
    @Column(name = "ppt_url", length = 500)
    private String pptUrl;

    /**
     * Web版是否已发布
     */
    @Column(name = "web_published", nullable = false)
    private Boolean webPublished;

    /**
     * 文件同步状态：synced / pending_regenerate
     */
    @Column(name = "file_status", nullable = false, length = 20)
    private String fileStatus;

    /**
     * 综合评分（默认4.5）
     */
    @Column(name = "rating", precision = 2, scale = 1)
    private BigDecimal rating;

    /**
     * 浏览次数
     */
    @Column(name = "view_count", nullable = false)
    private Integer viewCount;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 更新前自动设置更新时间
     */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
