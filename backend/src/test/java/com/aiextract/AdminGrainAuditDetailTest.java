package com.aiextract;

import com.aiextract.config.DomainConfigLoader;
import com.aiextract.config.PromptLoader;
import com.aiextract.model.AdminAuditLog;
import com.aiextract.model.ExperienceGrain;
import com.aiextract.repository.*;
import com.aiextract.service.AdminGrainService;
import com.aiextract.service.DashScopeEmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回归：admin_audit_log.detail 是 JSONB，颗粒编辑曾写入
 * {@code [expertThought, ...]}，提交时 22P02 把整个编辑事务回滚。
 */
@ExtendWith(MockitoExtension.class)
class AdminGrainAuditDetailTest {

    @Mock private ExperienceGrainRepository grainRepository;
    @Mock private SkillRepository skillRepository;
    @Mock private DashScopeEmbeddingService embeddingService;
    @Mock private GrainEditHistoryRepository editHistoryRepository;
    @Mock private AdminAuditLogRepository auditLogRepository;
    @Mock private PromptLoader promptLoader;
    @Mock private DomainConfigLoader domainConfigLoader;
    @Mock private KnowledgeGapRepository knowledgeGapRepository;
    @Mock private CandidateGrainRepository candidateGrainRepository;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks private AdminGrainService service;

    @BeforeEach
    void setUp() {
        // 自注入代理在单测里用同一个实例代替，事务语义由集成测试覆盖
        ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void editGrainWritesValidJsonAuditDetail() {
        UUID grainId = UUID.randomUUID();
        UUID spaceId = UUID.randomUUID();
        ExperienceGrain grain = ExperienceGrain.builder()
                .id(grainId).spaceId(spaceId)
                .sceneTag("价格异议")
                .expertThought("旧思考")
                .standardScript("旧话术")
                .weight(1.0).status("active")
                .build();

        when(grainRepository.findById(grainId)).thenReturn(Optional.of(grain));
        when(embeddingService.grainToText(any())).thenReturn("价格异议 旧思考 旧话术");
        when(embeddingService.embed(anyString())).thenReturn(new float[1024]);
        when(grainRepository.updateEmbedding(eq(grainId), anyString())).thenReturn(1);
        when(skillRepository.findBySpaceId(spaceId)).thenReturn(Optional.empty());

        service.updateGrain(grainId, Map.of("expertThought", "新思考"));

        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        String detail = captor.getValue().getDetail();
        assertDoesNotThrow(() -> objectMapper.readTree(detail), "detail 必须是合法 JSON");
        assertEquals("edit_grain", captor.getValue().getAction());
    }
}
