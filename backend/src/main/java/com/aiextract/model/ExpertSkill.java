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
 * 萃取师Skill实体类
 *
 * <p>对应 expert_skill 表，管理萃取师经验库中的每一位萃取师及其Skill状态。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "expert_skill")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpertSkill {

    /**
     * 萃取师唯一标识
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 萃取师姓名
     */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /**
     * 萃取师描述
     */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * 来源类型：interview=访谈 / document=文档 / hybrid=混合
     */
    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    /**
     * 领域ID，隔离不同域的萃取师经验
     */
    @Column(name = "domain", length = 64)
    private String domain;

    /**
     * 元访谈 session ID（来源为 interview 时关联）
     */
    @Column(name = "source_session_id")
    private UUID sourceSessionId;

    /**
     * 元访谈转录文本 / 素材原始内容，供分析管道处理
     */
    @Column(name = "source_content", columnDefinition = "TEXT")
    private String sourceContent;

    /**
     * 风格标签（JSON数组）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "style_tags", columnDefinition = "JSONB")
    private String styleTags;

    /**
     * 行业标签（JSON数组）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "industry_tags", columnDefinition = "JSONB")
    private String industryTags;

    /**
     * 从业年限
     */
    @Column(name = "seniority", length = 50)
    private String seniority;

    /**
     * 萃取师Skill文件URL
     */
    @Column(name = "skill_file", length = 500)
    private String skillFile;

    /**
     * 关联的报告ID
     */
    @Column(name = "report_id")
    private UUID reportId;

    /**
     * 已提取颗粒数
     */
    @Column(name = "grain_count", nullable = false)
    private Integer grainCount;

    /**
     * 萃取师状态：pending=待处理 / analyzing=分析中 / extracting=提取中 / active=活跃 / failed=失败
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

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
