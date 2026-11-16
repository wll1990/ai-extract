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
import java.util.Set;
import java.util.UUID;

/**
 * 导出任务实体 —— 评测报告与培训批次共用。
 *
 * <p>导出走「建任务 → 轮询 → 就绪后授权下载」，不在 HTTP 线程里生成文件。
 * 端点实现归 P3（G8），本轮先把表建好，避免上线补 DDL。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Entity
@Table(name = "export_job")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportJob {

    /** 主键 */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** 所属企业 ID */
    @Column(name = "company_id", nullable = false)
    private UUID companyId;

    /** 来源类型：evaluation_run/training_cohort */
    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    /** 来源 ID */
    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    /** 状态：running/done/failed */
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_RUNNING;

    /** 导出文件路径 */
    @Column(name = "file_path", length = 1000)
    private String filePath;

    /** 失败原因 */
    @Column(name = "error_message", columnDefinition = "TEXT")
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

    /** 完成时间（done/failed） */
    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 已领取次数（失败重试上限） */
    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    /** 领取者（workerId），条件更新必须带 owner */
    @Column(name = "lease_owner", length = 100)
    private String leaseOwner;

    /** 租约到期时间；过期后可被重新领取 */
    @Column(name = "lease_expires_at")
    private LocalDateTime leaseExpiresAt;

    /** 导出行数 */
    @Column(name = "row_count")
    private Integer rowCount;

    // ========== 来源类型常量 ==========

    public static final String SOURCE_EVALUATION_RUN = "evaluation_run";
    public static final String SOURCE_TRAINING_COHORT = "training_cohort";

    private static final Set<String> SOURCE_TYPES = Set.of(SOURCE_EVALUATION_RUN, SOURCE_TRAINING_COHORT);

    // ========== 状态常量 ==========

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    /** 判断来源类型是否合法。 */
    public static boolean isValidSourceType(String sourceType) {
        return sourceType != null && SOURCE_TYPES.contains(sourceType);
    }

    /** 是否已就绪可下载 */
    public boolean isDone() {
        return STATUS_DONE.equals(status);
    }

    /** 更新前自动刷新更新时间 */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
