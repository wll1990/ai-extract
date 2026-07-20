package com.aiextract.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 工具实体类
 *
 * <p>对应 tool 表，存储与报告关联的销售工具文件，
 * 包括海报、卡片、检查清单和剧本。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "tool")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tool {

    /**
     * 工具唯一标识
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
     * 关联的报告ID
     */
    @Column(name = "report_id")
    private UUID reportId;

    /**
     * 工具类型：poster=海报 / card=卡片 / checklist=检查清单 / script=剧本
     */
    @Column(name = "type", nullable = false, length = 30)
    private String type;

    /**
     * 工具名称
     */
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /**
     * 文件存储地址
     */
    @Column(name = "file_url", length = 500)
    private String fileUrl;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
