package com.aiextract.model;

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
 * 案例批量上传任务实体 — 追踪「文档上传 → AI 萃取案例」异步流程的状态。
 *
 * <p>与 {@link KnowledgeUploadTask} 对称：上传接口存文件后建 task(extracting) 并返回
 * taskId，前端轮询状态；{@code @Async} 萃取完成后更新状态并写入产出条数。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Entity
@Table(name = "case_upload_task")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseUploadTask {

    /** 任务唯一标识 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属企业 ID，NULL=平台公共上传（super_admin） */
    @Column(name = "company_id")
    private UUID companyId;

    /** 上传文件名 */
    @Column(name = "file_name", length = 500)
    private String fileName;

    /** OSS 对象 key（断点续传时重新解析，经 FileParseService 生成预签名 URL 后交 ai-service） */
    @Column(name = "file_path", length = 1000)
    private String filePath;

    /** 任务状态：extracting/done/failed */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 产出案例条数 */
    @Column(name = "produced_count", nullable = false)
    @Builder.Default
    private Integer producedCount = 0;

    /** 文档章节总数（结构解析后写入） */
    @Column(name = "total_sections", nullable = false)
    @Builder.Default
    private Integer totalSections = 0;

    /** 已处理章节数（断点续传的检查点） */
    @Column(name = "processed_sections", nullable = false)
    @Builder.Default
    private Integer processedSections = 0;

    /** 素材类型：internal=企业自有资料，book=外部书籍/课程（书籍案例按二手案例处理） */
    @Column(name = "source_kind", nullable = false, length = 20)
    @Builder.Default
    private String sourceKind = "internal";

    /** 被跳过的候选数（重复/结果不明/超出上限），页面提示用 */
    @Column(name = "skipped_count", nullable = false)
    @Builder.Default
    private Integer skippedCount = 0;

    /** 提取失败章节数（模型调用失败等），页面明确提示「部分章节未提取」 */
    @Column(name = "failed_sections", nullable = false)
    @Builder.Default
    private Integer failedSections = 0;

    /** 处理说明（为什么跳过、为什么建议拆书上传） */
    @Column(name = "guide_message", columnDefinition = "TEXT")
    private String guideMessage;

    /** 失败原因（status=failed 时展示给用户） */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 创建人 ID */
    @Column(name = "created_by")
    private UUID createdBy;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // ========== 状态常量 ==========

    public static final String STATUS_EXTRACTING = "extracting";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    /** 更新前自动设置更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
