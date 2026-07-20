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
 * 萃取师文档实体类
 *
 * <p>对应 expert_document 表，管理萃取师上传的文件材料及其解析状态。</p>
 *
 * @author AI Extract Team
 * @since 2026-06-29
 */
@Entity
@Table(name = "expert_document")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpertDocument {

    /**
     * 文档唯一标识
     */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * 所属萃取师ID
     */
    @Column(name = "expert_id", nullable = false)
    private UUID expertId;

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
    @Column(name = "file_type", length = 50)
    private String fileType;

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
     * 文档状态：uploaded=已上传 / parsing=解析中 / parsed=已解析 / failed=解析失败 / pending_manual=待人工处理
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
