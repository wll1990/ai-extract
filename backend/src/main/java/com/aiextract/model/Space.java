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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 个人空间实体类
 *
 * <p>对应 space 表，每个用户拥有一个个人经验空间，
 * 承载访谈会话、萃取报告、经验颗粒和AI分身等核心业务数据。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "space")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Space {

    /**
     * 空间唯一标识
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 所属用户ID
     */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /**
     * 空间标题
     */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /**
     * 空间描述（展示为销冠头衔）
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * 标签（JSON数组）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "JSONB")
    private String tags;

    /**
     * 是否公开
     */
    @Column(name = "is_public", nullable = false)
    private Boolean isPublic;

    /**
     * 空间状态：active=活跃 / paused=暂停 / archived=归档
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

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
