package com.aiextract;

import com.aiextract.exception.BusinessException;
import com.aiextract.model.TrainingPlan;
import com.aiextract.model.TrainingTask;
import com.aiextract.repository.TrainingPlanRepository;
import com.aiextract.repository.TrainingTaskRepository;
import com.aiextract.service.TrainingAccessService;
import com.aiextract.service.TrainingAuditService;
import com.aiextract.service.TrainingContentSnapshotService;
import com.aiextract.service.TrainingPlanSnapshotRepairService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 已发布版本快照修复：阅读任务重建，微练/陪练跳过，跨企业 404。 */
@ExtendWith(MockitoExtension.class)
class TrainingPlanSnapshotRepairServiceTest {

    @Mock private TrainingPlanRepository planRepository;
    @Mock private TrainingTaskRepository taskRepository;
    @Mock private TrainingContentSnapshotService snapshotService;
    @Mock private TrainingAccessService accessService;
    @Mock private TrainingAuditService auditService;

    private final UUID companyId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    private TrainingPlanSnapshotRepairService service() {
        return new TrainingPlanSnapshotRepairService(planRepository, taskRepository, snapshotService,
                accessService, auditService, new ObjectMapper());
    }

    @Test
    void repair_rebuildsReading_skipsDrill() throws Exception {
        UUID readingId = UUID.randomUUID();
        TrainingTask reading = TrainingTask.builder()
                .id(readingId).title("读知识").type(TrainingTask.TYPE_READING)
                .resourceType(TrainingTask.RESOURCE_KNOWLEDGE).resourceId(UUID.randomUUID())
                .snapshotVersion(1).build();
        TrainingTask drill = TrainingTask.builder()
                .id(UUID.randomUUID()).title("微练").type(TrainingTask.TYPE_DRILL)
                .resourceType(TrainingTask.RESOURCE_SKILL).resourceId(UUID.randomUUID()).build();
        when(accessService.requireCompanyId()).thenReturn(companyId);
        when(planRepository.findById(planId)).thenReturn(Optional.of(
                TrainingPlan.builder().id(planId).companyId(companyId).name("计划").build()));
        when(taskRepository.findByPlanIdAndVersionOrderByPhaseNoAscOrderNoAsc(planId, 2))
                .thenReturn(List.of(reading, drill));
        when(snapshotService.build(eq(reading), eq(companyId))).thenReturn(Map.of("title", "读知识"));

        var result = service().repair(planId, 2);

        assertEquals(1, result.repaired());
        assertEquals(1, result.skipped());
        assertEquals(0, result.failed());
        assertEquals(2, reading.getSnapshotVersion());
        verify(auditService).record(eq("training.snapshot.repair"), eq("training_plan"),
                eq(planId), any());
    }

    @Test
    void repair_otherCompanyPlan_404() {
        when(accessService.requireCompanyId()).thenReturn(companyId);
        when(planRepository.findById(planId)).thenReturn(Optional.of(
                TrainingPlan.builder().id(planId).companyId(UUID.randomUUID()).name("别家").build()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service().repair(planId, 1));

        assertEquals(404, ex.getErrorCode());
    }
}
