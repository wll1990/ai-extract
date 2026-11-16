package com.aiextract;

import com.aiextract.config.TokenContext;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.ExportJob;
import com.aiextract.model.TrainingCohort;
import com.aiextract.repository.ExportJobRepository;
import com.aiextract.repository.TrainingExportRepository;
import com.aiextract.service.TrainingAccessService;
import com.aiextract.service.TrainingAuditService;
import com.aiextract.service.TrainingExportService;
import com.aiextract.service.TrainingNotificationService;
import com.aiextract.service.export.ExportStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 导出任务：建任务、生成 CSV、行数上限、完成回写。
 */
@ExtendWith(MockitoExtension.class)
class TrainingExportServiceTest {

    @Mock private ExportJobRepository exportJobRepository;
    @Mock private TrainingExportRepository exportRepository;
    @Mock private TrainingAccessService accessService;
    @Mock private TrainingAuditService auditService;
    @Mock private TrainingNotificationService notificationService;
    @Mock private ExportStorage exportStorage;

    private TrainingExportService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final UUID cohortId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private static final String OWNER = "worker-1";

    @BeforeEach
    void setUp() {
        service = new TrainingExportService(exportJobRepository, exportRepository, accessService,
                auditService, notificationService, exportStorage);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxRows", 100);
        ReflectionTestUtils.setField(service, "leaseSeconds", 900L);
        ReflectionTestUtils.setField(service, "maxAttempts", 3);
        TokenContext.set(userId, companyId);
        lenient().when(accessService.requireCompanyId()).thenReturn(companyId);
        lenient().when(accessService.assertCanReview(cohortId)).thenReturn(
                TrainingCohort.builder().id(cohortId).companyId(companyId).build());
    }

    @AfterEach
    void tearDown() {
        TokenContext.clear();
    }

    @Test
    void request_createsRunningJobAndAudits() {
        when(exportJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ExportJob job = service.requestCohortExport(cohortId);

        assertEquals(ExportJob.STATUS_RUNNING, job.getStatus());
        assertEquals(cohortId, job.getSourceId());
        assertEquals(companyId, job.getCompanyId());
        verify(auditService).record(eq("training.export.request"), anyString(), any(), any());
    }

    @Test
    void process_writesCsvAndCompleteWritesShortTransaction() {
        ExportJob job = runningJob();
        when(exportJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(exportRepository.findCohortExportRows(cohortId, 101)).thenReturn(List.<Object[]>of(
                new Object[]{"一期", "计划", "张三", "zhangsan", "in_progress", null, false,
                        1, "阶段一", "阅读", "reading", true, 1, "passed", null, 1,
                        "pass", "90", LocalDateTime.now(), "confirmed", "主管", LocalDateTime.now(),
                        null, null, null, null}));
        when(exportStorage.store(anyString(), any(), eq("text/csv"))).thenReturn("exports/key.csv");
        when(exportJobRepository.complete(eq(jobId), eq(OWNER), anyString(), eq(1))).thenReturn(1);

        // CSV 生成不带事务；状态由独立的短事务方法写入
        TrainingExportService.ProcessOutcome outcome = service.process(jobId, OWNER);
        service.complete(jobId, OWNER, outcome);

        verify(exportJobRepository).complete(eq(jobId), eq(OWNER), anyString(), eq(1));
        verify(notificationService).notifyUser(eq(userId), eq(companyId),
                eq(com.aiextract.model.UserNotification.TYPE_EXPORT_READY), anyString(), anyString(),
                anyString(), anyString(), any(), anyString());
    }

    @Test
    void process_rowLimitExceeded_throwsAndLeavesFailToWorker() {
        ExportJob job = runningJob();
        when(exportJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        List<Object[]> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 101; i++) {
            tooMany.add(new Object[]{"x"});
        }
        when(exportRepository.findCohortExportRows(cohortId, 101)).thenReturn(tooMany);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.process(jobId, OWNER));

        assertEquals("EXPORT_ROW_LIMIT", ex.getSubCode());
        // 不在 process 内先 fail 再抛（否则 fail 随异常回滚）
        verify(exportJobRepository, org.mockito.Mockito.never())
                .failOrRequeue(any(), anyString(), anyString(), anyString());

        // Worker 兜底走独立短事务：重试耗尽才落 failed 并通知
        job.setAttempts(3);
        when(exportJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(exportJobRepository.failOrRequeue(jobId, OWNER, ExportJob.STATUS_FAILED, "超出上限"))
                .thenReturn(1);
        service.fail(jobId, OWNER, "超出上限");
        verify(exportJobRepository).failOrRequeue(jobId, OWNER, ExportJob.STATUS_FAILED, "超出上限");
        verify(notificationService).notifyUser(eq(userId), eq(companyId),
                eq(com.aiextract.model.UserNotification.TYPE_EXPORT_FAILED), anyString(),
                anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void process_expiredLease_skips() {
        ExportJob job = runningJob();
        job.setLeaseExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(exportJobRepository.findById(jobId)).thenReturn(Optional.of(job));

        org.junit.jupiter.api.Assertions.assertNull(service.process(jobId, OWNER));
        verify(exportRepository, org.mockito.Mockito.never())
                .findCohortExportRows(any(), anyInt());
    }

    private ExportJob runningJob() {
        return ExportJob.builder().id(jobId).companyId(companyId)
                .sourceType(ExportJob.SOURCE_TRAINING_COHORT).sourceId(cohortId)
                .status(ExportJob.STATUS_RUNNING).leaseOwner(OWNER).createdBy(userId)
                .leaseExpiresAt(LocalDateTime.now().plusMinutes(10))
                .attempts(1).createdAt(LocalDateTime.now()).build();
    }
}
