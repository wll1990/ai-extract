package com.aiextract;

import com.aiextract.config.PromptLoader;
import com.aiextract.dto.IndustryCaseRequest;
import com.aiextract.dto.KnowledgeEntryRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.AdminAuditLog;
import com.aiextract.model.GrainEditHistory;
import com.aiextract.model.IndustryCase;
import com.aiextract.model.KnowledgeEntry;
import com.aiextract.repository.AdminAuditLogRepository;
import com.aiextract.repository.GrainEditHistoryRepository;
import com.aiextract.repository.IndustryCaseRepository;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.service.ChatStreamAdapter;
import com.aiextract.service.DashScopeEmbeddingService;
import com.aiextract.service.KnowledgeCaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * KnowledgeCaseService 单元测试 — Mockito mock repository，验证状态流转 / 复制 / 版本化 / 审计调用。
 *
 * <p>self 自注入代理用反射指回本实例（单测无 Spring 事务管理器，@Transactional 为 no-op，
 * 只需验证业务逻辑）。embeddingService.embed 统一 lenient stub，避免 reEmbed 内被吞的 NPE 噪声。</p>
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeCaseServiceTest {

    @Mock
    private KnowledgeEntryRepository knowledgeRepository;
    @Mock
    private IndustryCaseRepository caseRepository;
    @Mock
    private DashScopeEmbeddingService embeddingService;
    @Mock
    private AdminAuditLogRepository auditLogRepository;
    @Mock
    private GrainEditHistoryRepository editHistoryRepository;
    @Mock
    private ChatStreamAdapter chatStreamAdapter;
    @Mock
    private PromptLoader promptLoader;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private KnowledgeCaseService service;

    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "self", service);
        lenient().when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f});
    }

    @Test
    void persistRevision_activeEntry_keepsSourceDocumentAndTask() {
        UUID id = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        KnowledgeEntry old = KnowledgeEntry.builder()
                .id(id)
                .companyId(companyId)
                .sourceType(KnowledgeEntry.SOURCE_TYPE_TENANT)
                .category("methodology")
                .title("旧标题")
                .content("旧内容")
                .version(1)
                .status(KnowledgeEntry.STATUS_ACTIVE)
                .source("《产品手册.pdf》 · 第1章")
                .sourceTaskId(taskId)
                .sourceDocument("产品手册.pdf")
                .effectiveAt(LocalDateTime.now())
                .expireAt(LocalDateTime.now().plusDays(30))
                .build();
        when(knowledgeRepository.findById(id)).thenReturn(Optional.of(old));
        when(knowledgeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeEntry draft = service.persistRevision(
                id, "新标题", "新的正文内容，包含具体数字和适用边界，确保可以进入审批。", "methodology", "压缩");

        assertEquals(2, draft.getVersion());
        assertEquals(taskId, draft.getSourceTaskId(), "新版本必须继承来源上传任务");
        assertEquals("产品手册.pdf", draft.getSourceDocument(), "新版本必须继承来源文档");
    }

    // ==================== 知识 CRUD ====================

    @Test
    void createKnowledge_defaultsStatusPending() {
        KnowledgeEntry result = service.createKnowledge(knowledgeRequest(), companyId);

        assertEquals(KnowledgeEntry.STATUS_PENDING, result.getStatus());
        assertEquals(1, result.getVersion());
        assertEquals(companyId, result.getCompanyId());
        // pending 不写向量（审批时才写），避免 create+approve 双重 embedding
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(auditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void createKnowledge_honorsExplicitStatus() {
        KnowledgeEntryRequest req = knowledgeRequest();
        req.setStatus(KnowledgeEntry.STATUS_ACTIVE);

        KnowledgeEntry result = service.createKnowledge(req, companyId);

        assertEquals(KnowledgeEntry.STATUS_ACTIVE, result.getStatus());
        // 显式 active 创建应立即写向量（可检索）；索引状态写回不算
        verify(embeddingService, org.mockito.Mockito.atLeastOnce()).embed(anyString());
    }

    @Test
    void createKnowledge_rejectsIllegalStatus() {
        KnowledgeEntryRequest req = knowledgeRequest();
        req.setStatus("deprecated");

        assertThrows(BusinessException.class, () -> service.createKnowledge(req, companyId));
    }

    @Test
    void createKnowledge_rejectsIllegalCategory() {
        KnowledgeEntryRequest req = knowledgeRequest();
        req.setCategory("nonexistent");

        assertThrows(BusinessException.class, () -> service.createKnowledge(req, companyId));
    }

    @Test
    void deprecateKnowledge_setsDeprecated() {
        UUID id = UUID.randomUUID();
        KnowledgeEntry existing = knowledgeEntity(id, KnowledgeEntry.STATUS_ACTIVE);
        when(knowledgeRepository.findById(id)).thenReturn(Optional.of(existing));

        service.deprecateKnowledge(id);

        assertEquals(KnowledgeEntry.STATUS_DEPRECATED, existing.getStatus());
        verify(knowledgeRepository).save(existing);
        verify(auditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void approveKnowledge_withoutExpireAt_throws() {
        UUID id = UUID.randomUUID();
        when(knowledgeRepository.findById(id))
                .thenReturn(Optional.of(knowledgeEntity(id, KnowledgeEntry.STATUS_PENDING)));

        assertThrows(BusinessException.class, () -> service.approveKnowledge(id, null));
    }

    @Test
    void approveKnowledge_setsActiveAndExpireAt() {
        UUID id = UUID.randomUUID();
        when(knowledgeRepository.findById(id))
                .thenReturn(Optional.of(knowledgeEntity(id, KnowledgeEntry.STATUS_PENDING)));
        LocalDateTime expire = LocalDateTime.now().plusDays(30);

        KnowledgeEntry result = service.approveKnowledge(id, expire);

        assertEquals(KnowledgeEntry.STATUS_ACTIVE, result.getStatus());
        assertEquals(expire, result.getExpireAt());
        verify(embeddingService, org.mockito.Mockito.atLeastOnce()).embed(anyString());
    }

    @Test
    void approveKnowledge_fallsBackToExistingExpireAt() {
        UUID id = UUID.randomUUID();
        LocalDateTime existingExpire = LocalDateTime.now().plusDays(10);
        KnowledgeEntry existing = knowledgeEntity(id, KnowledgeEntry.STATUS_PENDING);
        existing.setExpireAt(existingExpire);
        when(knowledgeRepository.findById(id)).thenReturn(Optional.of(existing));

        KnowledgeEntry result = service.approveKnowledge(id, null);

        assertEquals(KnowledgeEntry.STATUS_ACTIVE, result.getStatus());
        assertEquals(existingExpire, result.getExpireAt());
    }

    @Test
    void copyPublicKnowledgeToCompany_createsPendingCopy() {
        UUID sourceId = UUID.randomUUID();
        KnowledgeEntry publicEntry = KnowledgeEntry.builder()
                .id(sourceId).companyId(null).sourceType("public").category("methodology")
                .title("BANT").content("四问法").version(1).status(KnowledgeEntry.STATUS_ACTIVE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(knowledgeRepository.findById(sourceId)).thenReturn(Optional.of(publicEntry));

        KnowledgeEntry copy = service.copyPublicKnowledgeToCompany(sourceId, companyId);

        assertEquals(companyId, copy.getCompanyId());
        assertEquals(KnowledgeEntry.STATUS_PENDING, copy.getStatus());
        assertEquals(1, copy.getVersion());
        assertEquals("BANT", copy.getTitle());
        assertEquals("四问法", copy.getContent());
        // 复制后 pending，待企业审批时才写向量
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(auditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void copyPublicKnowledgeToCompany_rejectsPrivateSource() {
        UUID sourceId = UUID.randomUUID();
        KnowledgeEntry privateEntry = knowledgeEntity(sourceId, KnowledgeEntry.STATUS_ACTIVE);
        privateEntry.setCompanyId(UUID.randomUUID());
        when(knowledgeRepository.findById(sourceId)).thenReturn(Optional.of(privateEntry));

        assertThrows(BusinessException.class,
                () -> service.copyPublicKnowledgeToCompany(sourceId, companyId));
    }

    // ==================== 知识版本化 ====================

    @Test
    void suggestRevision_keepsOldActiveUntilApproval() {
        UUID id = UUID.randomUUID();
        KnowledgeEntry current = knowledgeEntity(id, KnowledgeEntry.STATUS_ACTIVE);
        when(knowledgeRepository.findById(id)).thenReturn(Optional.of(current));
        when(promptLoader.format(eq("material_knowledge_revise.md"), anyMap())).thenReturn("prompt");
        when(chatStreamAdapter.chat(anyString()))
                .thenReturn("{\"title\":\"新BANT\",\"content\":\"新的四问法内容\",\"category\":\"methodology\"}");

        KnowledgeEntry draft = service.suggestRevision(id, "更新四问法表述");

        // 审批前旧版 continue active，新版 draft 通过 supersedesId 指回旧版
        assertEquals(KnowledgeEntry.STATUS_ACTIVE, current.getStatus());
        assertEquals(KnowledgeEntry.STATUS_DRAFT, draft.getStatus());
        assertEquals(id, draft.getSupersedesId());
        assertEquals(2, draft.getVersion());
        assertEquals("新BANT", draft.getTitle());

        // 两阶段：审批后新版 active，向量化成功才下线旧版
        when(knowledgeRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
        draft.setExpireAt(java.time.LocalDateTime.now().plusDays(30));
        service.approveKnowledge(draft.getId(), draft.getExpireAt());
        assertEquals(KnowledgeEntry.STATUS_ACTIVE, current.getStatus(),
                "旧版必须等新版向量化成功后才下线，避免检索真空");
        draft.setEmbeddingStatus("indexed");
        when(knowledgeRepository.findByRootIdAndStatus(any(), eq(KnowledgeEntry.STATUS_ACTIVE)))
                .thenReturn(java.util.List.of(current, draft));
        service.finalizeKnowledgeIndex(draft.getId());
        assertEquals(KnowledgeEntry.STATUS_DEPRECATED, current.getStatus());
        verify(editHistoryRepository).save(any(GrainEditHistory.class));
        // 一次 suggest_revision，一次 approve_knowledge
        verify(auditLogRepository, times(2)).save(any(AdminAuditLog.class));
    }

    @Test
    void suggestRevision_draft_updatesInPlace() {
        UUID id = UUID.randomUUID();
        KnowledgeEntry draft = knowledgeEntity(id, KnowledgeEntry.STATUS_DRAFT);
        when(knowledgeRepository.findById(id)).thenReturn(Optional.of(draft));
        when(promptLoader.format(eq("material_knowledge_revise.md"), anyMap())).thenReturn("prompt");
        when(chatStreamAdapter.chat(anyString()))
                .thenReturn("{\"title\":\"新BANT\",\"content\":\"新的四问法内容\",\"category\":\"methodology\"}");

        KnowledgeEntry result = service.suggestRevision(id, "改写表述");

        // 草稿还没生效：AI 改写直接覆盖当前条目，不产生子版本
        org.junit.jupiter.api.Assertions.assertSame(draft, result);
        assertEquals(id, result.getId());
        assertEquals("新BANT", result.getTitle());
        assertEquals(KnowledgeEntry.STATUS_DRAFT, result.getStatus());
        assertEquals(null, result.getSupersedesId());
    }

    @Test
    void suggestRevision_rejectsIllegalAiCategory() {
        UUID id = UUID.randomUUID();
        KnowledgeEntry current = knowledgeEntity(id, KnowledgeEntry.STATUS_ACTIVE);
        when(knowledgeRepository.findById(id)).thenReturn(Optional.of(current));
        when(promptLoader.format(eq("material_knowledge_revise.md"), anyMap())).thenReturn("prompt");
        when(chatStreamAdapter.chat(anyString()))
                .thenReturn("{\"title\":\"新BANT\",\"content\":\"内容\",\"category\":\"invalid\"}");

        assertThrows(BusinessException.class, () -> service.suggestRevision(id, "指令"));

        verify(knowledgeRepository, never()).save(any(KnowledgeEntry.class));
    }

    @Test
    void suggestCaseRevision_draft_updatesInPlace() {
        UUID id = UUID.randomUUID();
        IndustryCase draft = IndustryCase.builder()
                .id(id).companyId(companyId).title("原案例").result("win")
                .version(1).status(IndustryCase.STATUS_DRAFT)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        when(caseRepository.findById(id)).thenReturn(Optional.of(draft));
        when(promptLoader.format(eq("material_case_revise.md"), anyMap())).thenReturn("prompt");
        when(chatStreamAdapter.chat(anyString()))
                .thenReturn("{\"title\":\"新案例\",\"objection\":\"价格异议\",\"keyActions\":\"拆分总拥有成本\"}");

        IndustryCase result = service.suggestCaseRevision(id, "改写");

        org.junit.jupiter.api.Assertions.assertSame(draft, result);
        assertEquals(id, result.getId());
        assertEquals("新案例", result.getTitle());
        assertEquals(IndustryCase.STATUS_DRAFT, result.getStatus());
        assertEquals(null, result.getSupersedesId());
    }

    @Test
    void suggestCaseRevision_profileAsObject_persistsValidJson() {
        UUID id = UUID.randomUUID();
        IndustryCase draft = IndustryCase.builder()
                .id(id).companyId(companyId).title("原案例").result("win")
                .version(1).status(IndustryCase.STATUS_DRAFT)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        when(caseRepository.findById(id)).thenReturn(Optional.of(draft));
        when(promptLoader.format(eq("material_case_revise.md"), anyMap())).thenReturn("prompt");
        when(chatStreamAdapter.chat(anyString())).thenReturn(
                "{\"title\":\"新案例\",\"customerProfile\":{\"industry\":\"制造业\"},"
                        + "\"objection\":\"价格异议\"}");

        IndustryCase result = service.suggestCaseRevision(id, "改写");

        assertTrue(result.getCustomerProfile().contains("制造业"));
        assertTrue(result.getCustomerProfile().trim().startsWith("{"));
    }

    @Test
    void suggestCaseRevision_profileAsEscapedString_persistsValidJson() {
        UUID id = UUID.randomUUID();
        IndustryCase draft = IndustryCase.builder()
                .id(id).companyId(companyId).title("原案例").result("win")
                .version(1).status(IndustryCase.STATUS_DRAFT)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
        when(caseRepository.findById(id)).thenReturn(Optional.of(draft));
        when(promptLoader.format(eq("material_case_revise.md"), anyMap())).thenReturn("prompt");
        when(chatStreamAdapter.chat(anyString())).thenReturn(
                "{\"title\":\"新案例\",\"customerProfile\":\"{\\\"industry\\\":\\\"制造业\\\"}\"}");

        IndustryCase result = service.suggestCaseRevision(id, "改写");

        assertTrue(result.getCustomerProfile().contains("制造业"));
    }

    @Test
    void finalizeCaseIndex_oldVersionDoesNotDeprecateNewerActive() {
        UUID root = UUID.randomUUID();
        IndustryCase v1 = versionedCase(root, 1, "active");
        IndustryCase v2 = versionedCase(root, 2, "active");
        when(caseRepository.findById(v1.getId())).thenReturn(Optional.of(v1));
        when(caseRepository.findByRootIdAndStatus(root, IndustryCase.STATUS_ACTIVE))
                .thenReturn(List.of(v1, v2));

        service.finalizeCaseIndex(v1.getId());

        assertEquals(IndustryCase.STATUS_ACTIVE, v2.getStatus(), "旧版收尾不能下线更高版本");
        verify(caseRepository, never()).save(v2);
    }

    @Test
    void finalizeCaseIndex_newerVersionDeprecatesOld() {
        UUID root = UUID.randomUUID();
        IndustryCase v1 = versionedCase(root, 1, "active");
        IndustryCase v2 = versionedCase(root, 2, "active");
        when(caseRepository.findById(v2.getId())).thenReturn(Optional.of(v2));
        when(caseRepository.findByRootIdAndStatus(root, IndustryCase.STATUS_ACTIVE))
                .thenReturn(List.of(v1, v2));

        service.finalizeCaseIndex(v2.getId());

        assertEquals(IndustryCase.STATUS_DEPRECATED, v1.getStatus());
        assertEquals(IndustryCase.STATUS_ACTIVE, v2.getStatus());
    }

    private IndustryCase versionedCase(UUID root, int version, String status) {
        return IndustryCase.builder()
                .id(UUID.randomUUID()).rootId(root).companyId(companyId)
                .title("v" + version).result("win").version(version).status(status)
                .embeddingStatus("indexed")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    // ==================== 案例 CRUD ====================

    @Test
    void createCase_defaultsPending() {
        IndustryCase result = service.createCase(caseRequest(), companyId);

        assertEquals(IndustryCase.STATUS_PENDING, result.getStatus());
        assertEquals(1, result.getVersion());
        assertEquals(companyId, result.getCompanyId());
        // pending 不写向量
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(auditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void createCase_rejectsIllegalResult() {
        IndustryCaseRequest req = caseRequest();
        req.setResult("invalid");

        assertThrows(BusinessException.class, () -> service.createCase(req, companyId));
    }

    @Test
    void createCase_rejectsPlainTextCustomerProfile() {
        IndustryCaseRequest req = caseRequest();
        req.setCustomerProfile("制造业客户，预算审批链为采购+财务。");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.createCase(req, companyId));

        // JSONB 列传纯文本必须 400，而不是提交阶段炸成 500
        assertEquals(400, e.getErrorCode());
        verify(caseRepository, never()).save(any(IndustryCase.class));
    }

    @Test
    void createCase_acceptsJsonCustomerProfile() {
        IndustryCaseRequest req = caseRequest();
        req.setCustomerProfile("{\"industry\":\"制造业\"}");

        IndustryCase result = service.createCase(req, companyId);

        assertEquals("{\"industry\":\"制造业\"}", result.getCustomerProfile());
    }

    @Test
    void updateCase_rejectsPlainTextDialogueQuote() {
        UUID id = UUID.randomUUID();
        IndustryCase existing = IndustryCase.builder()
                .id(id).companyId(companyId).title("案例").version(1)
                .status(IndustryCase.STATUS_PENDING)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(caseRepository.findById(id)).thenReturn(Optional.of(existing));
        IndustryCaseRequest req = caseRequest();
        req.setDialogueQuote("客户说太贵了");

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.updateCase(id, req));

        assertEquals(400, e.getErrorCode());
        verify(caseRepository, never()).save(any(IndustryCase.class));
    }

    @Test
    void deprecateCase_setsDeprecated() {
        UUID id = UUID.randomUUID();
        IndustryCase existing = IndustryCase.builder()
                .id(id).companyId(companyId).title("案例").version(1)
                .status(IndustryCase.STATUS_ACTIVE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(caseRepository.findById(id)).thenReturn(Optional.of(existing));

        service.deprecateCase(id);

        assertEquals(IndustryCase.STATUS_DEPRECATED, existing.getStatus());
        verify(auditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void updateCase_rejectsActiveCase() {
        UUID id = UUID.randomUUID();
        IndustryCase existing = IndustryCase.builder()
                .id(id).companyId(companyId).title("案例").version(1)
                .status(IndustryCase.STATUS_ACTIVE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(caseRepository.findById(id)).thenReturn(Optional.of(existing));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.updateCase(id, caseRequest()));

        assertEquals(400, e.getErrorCode());
        verify(caseRepository, never()).save(any(IndustryCase.class));
    }

    @Test
    void restoreCase_returnsToPreviousDraftStatus() {
        UUID id = UUID.randomUUID();
        IndustryCase existing = IndustryCase.builder()
                .id(id).companyId(companyId).title("案例").version(1)
                .status(IndustryCase.STATUS_DEPRECATED)
                .previousStatus(IndustryCase.STATUS_DRAFT)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(caseRepository.findById(id)).thenReturn(Optional.of(existing));

        IndustryCase result = service.restoreCase(id);

        // 放弃的草稿恢复后仍是草稿，不能绕过审批直接生效
        assertEquals(IndustryCase.STATUS_DRAFT, result.getStatus());
        verify(embeddingService, never()).embed(any());
    }

    @Test
    void restoreCase_activeGoesBackToActiveAndReembeds() {
        UUID id = UUID.randomUUID();
        IndustryCase existing = IndustryCase.builder()
                .id(id).companyId(companyId).title("案例").version(1).result("win")
                .status(IndustryCase.STATUS_DEPRECATED)
                .previousStatus(IndustryCase.STATUS_ACTIVE)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(caseRepository.findById(id)).thenReturn(Optional.of(existing));

        IndustryCase result = service.restoreCase(id);

        assertEquals(IndustryCase.STATUS_ACTIVE, result.getStatus());
        verify(embeddingService).embed(any());
    }

    @Test
    void approveCase_embedsOnActive() {
        UUID id = UUID.randomUUID();
        IndustryCase existing = IndustryCase.builder()
                .id(id).companyId(companyId).title("案例").version(1)
                .result("win").status(IndustryCase.STATUS_PENDING)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(caseRepository.findById(id)).thenReturn(Optional.of(existing));

        IndustryCase result = service.approveCase(id, null);

        assertEquals(IndustryCase.STATUS_ACTIVE, result.getStatus());
        // active 审批写向量；索引状态写回不算
        verify(embeddingService, org.mockito.Mockito.atLeastOnce()).embed(anyString());
    }

    @Test
    void caseEmbedText_usesDistinctiveFieldsNotGenericActions() {
        UUID id = UUID.randomUUID();
        IndustryCase existing = IndustryCase.builder()
                .id(id).companyId(companyId).title("拿下某高端制造企业的ERP大单").version(1)
                .result("win").status(IndustryCase.STATUS_PENDING)
                .customerProfile("{\"行业\":\"精密制造\",\"规模\":\"年产值20亿\"}")
                .objection("数据迁移风险")
                .keyActions("高层拜访+方案定制")
                .reusablePoints("标杆参观")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(caseRepository.findById(id)).thenReturn(Optional.of(existing));

        service.approveCase(id, null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(embeddingService).embed(captor.capture());
        String text = captor.getValue();
        // 高信息量字段：title 加权（出现两次）+ customerProfile 的 value + objection + reusablePoints
        assertEquals(2, text.split("拿下某高端制造企业的ERP大单", -1).length - 1);
        assertTrue(text.contains("精密制造"));
        assertTrue(text.contains("年产值20亿"));
        assertTrue(text.contains("数据迁移风险"));
        assertTrue(text.contains("标杆参观"));
        // 泛销售字段（keyActions）被剔除，避免「泛销售」基底噪音
        assertFalse(text.contains("高层拜访"));
    }

    // ==================== 工具 ====================

    private KnowledgeEntryRequest knowledgeRequest() {
        return KnowledgeEntryRequest.builder()
                .title("BANT 四问法")
                .category("methodology")
                .sourceType("public")
                .content("预算、权限、需求、时间线四要素。")
                .expireAt(LocalDateTime.now().plusDays(30))
                .build();
    }

    private IndustryCaseRequest caseRequest() {
        return IndustryCaseRequest.builder().title("拿下某银行大单").build();
    }

    private KnowledgeEntry knowledgeEntity(UUID id, String status) {
        return KnowledgeEntry.builder()
                .id(id).companyId(companyId).sourceType("public").category("methodology")
                .title("BANT").content("四问法").version(1).status(status)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
    }
}
