package com.aiextract.service;

import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ExportJob;
import com.aiextract.model.UserNotification;
import com.aiextract.repository.ExportJobRepository;
import com.aiextract.repository.TrainingCohortRepository;
import com.aiextract.repository.TrainingExportRepository;
import com.aiextract.service.export.ExportStorage;
import com.aiextract.util.CsvWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 批次导出 —— 建任务 → Worker 领取 → 生成 CSV → 完成后通知并给下载。
 *
 * <p>HTTP 线程只建任务，不在请求里生成文件；领取用 FOR UPDATE SKIP LOCKED + 租约，
 * 进程崩溃后租约过期可被重新领取。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrainingExportService {

    private static final String SOURCE_TRAINING_COHORT = ExportJob.SOURCE_TRAINING_COHORT;

    private static final String[] HEADERS = {
            "批次", "计划", "学员", "账号", "分配状态", "截止时间", "是否逾期",
            "阶段号", "阶段名", "任务", "类型", "必修", "轮次", "记录状态",
            "尝试次数", "最新结论", "最新总分", "交卷时间",
            "认证状态", "确认人", "确认时间", "撤销人", "撤销时间", "撤销理由", "补训原因"
    };

    private final ExportJobRepository exportJobRepository;
    private final TrainingExportRepository exportRepository;
    private final TrainingAccessService accessService;
    private final TrainingAuditService auditService;
    private final TrainingNotificationService notificationService;
    private final ExportStorage exportStorage;

    @Value("${app.training.export.enabled:true}")
    private boolean enabled;

    @Value("${app.training.export.max-rows:100000}")
    private int maxRows;

    @Value("${app.training.export.lease-seconds:900}")
    private long leaseSeconds;

    @Value("${app.training.export.max-attempts:3}")
    private int maxAttempts;

    @Transactional(rollbackFor = Exception.class)
    public ExportJob requestCohortExport(UUID cohortId) {
        if (!enabled) {
            throw new BusinessException(409, "导出功能未开启");
        }
        var cohort = accessService.assertCanReview(cohortId);
        var companyId = accessService.requireCompanyId();
        LocalDateTime now = LocalDateTime.now();
        ExportJob job = ExportJob.builder()
                .id(UUID.randomUUID())
                .companyId(companyId)
                .sourceType(SOURCE_TRAINING_COHORT)
                .sourceId(cohort.getId())
                .status(ExportJob.STATUS_RUNNING)
                .attempts(0)
                .createdBy(TokenContext.get())
                .createdAt(now)
                .updatedAt(now)
                .build();
        exportJobRepository.save(job);
        auditService.record("training.export.request", "training_export_job", job.getId(),
                Map.of("cohortId", cohortId.toString()));
        return job;
    }

    public ExportJob get(UUID id) {
        return exportJobRepository.findByIdAndCompanyIdAndSourceType(id,
                        accessService.requireCompanyId(), SOURCE_TRAINING_COHORT)
                .orElseThrow(() -> new BusinessException(404, "导出任务不存在"));
    }

    /** 下载信息：oss → url；local → 文件路径；都为空表示未就绪 */
    @Transactional(rollbackFor = Exception.class)
    public Download download(UUID id) {
        ExportJob job = get(id);
        if (!job.isDone() || job.getFilePath() == null) {
            throw BusinessException.conflict("EXPORT_NOT_READY", "导出尚未完成");
        }
        // 导出内容是学员姓名/账号/成绩等 PII，真正的「外发」动作必须留痕
        auditService.record("training.export.download", "training_export_job", job.getId(),
                Map.of("cohortId", job.getSourceId().toString(),
                        "rowCount", job.getRowCount() == null ? 0 : job.getRowCount()));
        String url = exportStorage.presignedUrl(job.getFilePath());
        Path local = exportStorage.localPath(job.getFilePath());
        return new Download(job, url, local);
    }


    public record Download(ExportJob job, String url, Path localPath) {
    }

    public long leaseSeconds() {
        return leaseSeconds;
    }

    /**
     * 领取一批可执行的导出任务（同一事务内 SELECT SKIP LOCKED + 条件 claim）。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<UUID> claimBatch(String owner, int limit) {
        List<UUID> ids = exportJobRepository.lockClaimableIds(limit);
        if (ids.isEmpty()) {
            return List.of();
        }
        LocalDateTime expires = LocalDateTime.now().plusSeconds(leaseSeconds);
        return ids.stream()
                .filter(id -> exportJobRepository.claim(id, owner, expires) == 1)
                .toList();
    }

    /** 处理结果：CSV 已生成并写入存储，等待短事务落库 */
    public record ProcessOutcome(String fileKey, int rowCount) {
    }

    /**
     * 生成并上传 CSV；由 Worker 在领取成功后调用。
     *
     * <p><b>故意不加 @Transactional</b>：CSV 构建与本地写盘/OSS 上传是文件与网络 IO，
     * 放进事务会长时间占用连接（CLAUDE.md 红线）。查询走各自的短事务，
     * 成功后由 {@link #complete}、失败由 {@link #fail} 用独立短事务落库。</p>
     */
    public ProcessOutcome process(UUID jobId, String owner) {
        ExportJob job = exportJobRepository.findById(jobId).orElse(null);
        if (job == null || !ExportJob.STATUS_RUNNING.equals(job.getStatus())
                || !owner.equals(job.getLeaseOwner())
                || job.getLeaseExpiresAt() == null
                || !job.getLeaseExpiresAt().isAfter(LocalDateTime.now())) {
            // 租约已失效/已被接管：旧实例不得继续写
            return null;
        }
        int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
        if (attempts > maxAttempts) {
            throw BusinessException.conflict("EXPORT_RETRY_EXHAUSTED",
                    "导出多次重试失败（" + attempts + " 次），请重新发起导出");
        }
        UUID cohortId = job.getSourceId();
        List<Object[]> rows = exportRepository.findCohortExportRows(cohortId, maxRows + 1);
        if (rows.size() > maxRows) {
            // 不在事务里 fail：交给 Worker 调 fail()（独立事务）落状态并发通知
            throw BusinessException.conflict("EXPORT_ROW_LIMIT",
                    "导出数据超过上限（" + maxRows + " 行），请缩小范围后重试");
        }

        CsvWriter csv = new CsvWriter();
        csv.append((Object[]) HEADERS);
        for (Object[] row : rows) {
            csv.append(row);
        }
        String key = "exports/training/" + job.getCompanyId() + "/" + job.getId() + ".csv";
        exportStorage.store(key, csv.toBytes(), "text/csv");
        return new ProcessOutcome(key, csv.rowCount() - 1);
    }

    /** 短事务：写 done + 通知（通知在事务提交后投递） */
    @Transactional(rollbackFor = Exception.class)
    public void complete(UUID jobId, String owner, ProcessOutcome outcome) {
        int updated = exportJobRepository.complete(jobId, owner, outcome.fileKey(), outcome.rowCount());
        if (updated == 0) {
            throw new IllegalStateException("导出完成写入失败（租约已失效）");
        }
        ExportJob job = exportJobRepository.findById(jobId).orElse(null);
        if (job != null) {
            notify(job, UserNotification.TYPE_EXPORT_READY, "培训名单导出已完成",
                    "可在导出列表下载（" + outcome.rowCount() + " 行）。");
        }
    }

    /**
     * 标记失败。
     *
     * <p>Worker 在事务外调用，必须用 REQUIRES_NEW 建独立事务：
     * Spring Data 的 {@code @Modifying} 查询不自带事务，否则直接抛
     * {@code TransactionRequiredException}，失败状态永远写不进去。</p>
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void fail(UUID jobId, String owner, String reason) {
        ExportJob job = exportJobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        int attempts = job.getAttempts() == null ? 0 : job.getAttempts();
        boolean exhausted = attempts >= maxAttempts;
        int updated = exportJobRepository.failOrRequeue(jobId, owner,
                exhausted ? ExportJob.STATUS_FAILED : ExportJob.STATUS_RUNNING, reason);
        // 租约已被接管时 updated=0，不能替别人发失败通知
        if (updated == 1 && exhausted) {
            notify(job, UserNotification.TYPE_EXPORT_FAILED, "培训导出失败", reason);
        }
    }

    private void notify(ExportJob job, String type, String title, String content) {
        if (job.getCreatedBy() == null) {
            return;
        }
        notificationService.notifyUser(job.getCreatedBy(), job.getCompanyId(), type, title, content,
                "/admin/training/cohorts/" + job.getSourceId(), "training_export_job", job.getId(),
                "training:export:" + job.getId() + ":" + type);
    }

    /** 导出列表所需的最小视图（Controller 直接映射实体，这里只保留语义注释） */
    public Map<String, Object> toView(ExportJob job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", job.getId().toString());
        map.put("sourceType", job.getSourceType());
        map.put("sourceId", job.getSourceId().toString());
        map.put("status", job.getStatus());
        map.put("rowCount", job.getRowCount());
        map.put("errorMessage", job.getErrorMessage());
        map.put("createdBy", job.getCreatedBy() != null ? job.getCreatedBy().toString() : null);
        map.put("createdAt", job.getCreatedAt());
        map.put("finishedAt", job.getFinishedAt());
        return map;
    }
}
