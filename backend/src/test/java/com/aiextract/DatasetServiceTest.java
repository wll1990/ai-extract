package com.aiextract;

import com.aiextract.dto.EvaluationDatasetRequest;
import com.aiextract.dto.EvaluationItemRequest;
import com.aiextract.exception.BusinessException;
import com.aiextract.model.EvaluationDataset;
import com.aiextract.model.EvaluationDatasetVersion;
import com.aiextract.model.EvaluationItem;
import com.aiextract.repository.EvaluationDatasetRepository;
import com.aiextract.repository.EvaluationDatasetVersionRepository;
import com.aiextract.repository.EvaluationItemRepository;
import com.aiextract.repository.EvaluationRunItemRepository;
import com.aiextract.service.DatasetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DatasetService 单元测试 —— Mockito mock repository，验证题集审核状态机。
 */
@ExtendWith(MockitoExtension.class)
class DatasetServiceTest {

    @Mock
    private EvaluationDatasetRepository datasetRepository;
    @Mock
    private EvaluationDatasetVersionRepository versionRepository;
    @Mock
    private EvaluationItemRepository itemRepository;
    @Mock
    private EvaluationRunItemRepository runItemRepository;

    @InjectMocks
    private DatasetService service;

    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(datasetRepository.save(any(EvaluationDataset.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(versionRepository.save(any(EvaluationDatasetVersion.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(itemRepository.save(any(EvaluationItem.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ==================== 创建 ====================

    @Test
    void createDataset_defaultsDraftVersion1() {
        EvaluationDataset result = service.createDataset(request(), companyId);

        assertEquals(EvaluationDataset.STATUS_DRAFT, result.getStatus());
        assertEquals(1, result.getCurrentVersion());
        assertEquals(companyId, result.getCompanyId());
        verify(datasetRepository).save(any(EvaluationDataset.class));
        verify(versionRepository).save(any(EvaluationDatasetVersion.class));
    }

    @Test
    void createDataset_rejectsIllegalPurpose() {
        EvaluationDatasetRequest req = request();
        req.setPurpose("invalid");
        assertThrows(BusinessException.class, () -> service.createDataset(req, companyId));
    }

    // ==================== 编辑（乐观锁 + 仅草稿） ====================

    @Test
    void updateDataset_rejectsNonDraft() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(dataset(id, EvaluationDataset.STATUS_PUBLISHED, 1)));

        assertThrows(BusinessException.class, () -> service.updateDataset(id, request()));
    }

    @Test
    void updateDataset_rejectsStaleVersion() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(dataset(id, EvaluationDataset.STATUS_DRAFT, 2)));
        EvaluationDatasetRequest req = request();
        req.setVersion(1);

        assertThrows(BusinessException.class, () -> service.updateDataset(id, req));
    }

    // ==================== 提交审核（完整性 + 用途一致性） ====================

    @Test
    void submitForReview_rejectsEmpty() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(dataset(id, EvaluationDataset.STATUS_DRAFT, 1)));
        when(itemRepository.findByDatasetIdAndVersionAndStatus(id, 1, EvaluationItem.STATUS_ACTIVE))
                .thenReturn(List.of());

        assertThrows(BusinessException.class, () -> service.submitForReview(id));
    }

    @Test
    void submitForReview_rejectsRegressionWithoutRetrievalExpected() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(dataset(id, EvaluationDataset.STATUS_DRAFT, 1)));
        EvaluationItem item = EvaluationItem.builder().id(UUID.randomUUID()).datasetId(id).version(1)
                .question("q").status(EvaluationItem.STATUS_ACTIVE).retrievalExpected(null).build();
        when(itemRepository.findByDatasetIdAndVersionAndStatus(id, 1, EvaluationItem.STATUS_ACTIVE))
                .thenReturn(List.of(item));

        assertThrows(BusinessException.class, () -> service.submitForReview(id));
    }

    @Test
    void submitForReview_setsInReview() {
        UUID id = UUID.randomUUID();
        EvaluationDataset ds = dataset(id, EvaluationDataset.STATUS_DRAFT, 1);
        when(datasetRepository.findById(id)).thenReturn(Optional.of(ds));
        EvaluationItem item = EvaluationItem.builder().id(UUID.randomUUID()).datasetId(id).version(1)
                .question("q").status(EvaluationItem.STATUS_ACTIVE).retrievalExpected("[\"术语-复购率\"]").build();
        when(itemRepository.findByDatasetIdAndVersionAndStatus(id, 1, EvaluationItem.STATUS_ACTIVE))
                .thenReturn(List.of(item));

        EvaluationDataset result = service.submitForReview(id);

        assertEquals(EvaluationDataset.STATUS_IN_REVIEW, result.getStatus());
    }

    // ==================== 审核 ====================

    @Test
    void reviewDataset_rejectWithoutReason_throws() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(dataset(id, EvaluationDataset.STATUS_IN_REVIEW, 1)));

        assertThrows(BusinessException.class, () -> service.reviewDataset(id, "reject", null));
    }

    @Test
    void reviewDataset_approve_setsApproved() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(dataset(id, EvaluationDataset.STATUS_IN_REVIEW, 1)));

        EvaluationDataset result = service.reviewDataset(id, "approve", null);

        assertEquals(EvaluationDataset.STATUS_APPROVED, result.getStatus());
    }

    // ==================== 发布 / 版本 ====================

    @Test
    void publishDataset_rejectsNotApproved() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(dataset(id, EvaluationDataset.STATUS_DRAFT, 1)));

        assertThrows(BusinessException.class, () -> service.publishDataset(id));
    }

    @Test
    void createVersion_rejectsNotPublished() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(dataset(id, EvaluationDataset.STATUS_DRAFT, 1)));

        assertThrows(BusinessException.class, () -> service.createVersion(id));
    }

    /** item_count 口径是「最新已发布版题数」：开出 v2 草稿后不能归零，否则列表显示 0 题。 */
    @Test
    void createVersion_keepsPublishedItemCount() {
        UUID id = UUID.randomUUID();
        EvaluationDataset ds = dataset(id, EvaluationDataset.STATUS_PUBLISHED, 1);
        ds.setItemCount(2);
        when(datasetRepository.findById(id)).thenReturn(Optional.of(ds));
        when(itemRepository.findByDatasetIdAndVersionAndStatus(id, 1, EvaluationItem.STATUS_ACTIVE))
                .thenReturn(List.of());

        EvaluationDataset result = service.createVersion(id);

        assertEquals(2, result.getCurrentVersion());
        assertEquals(EvaluationDataset.STATUS_DRAFT, result.getStatus());
        assertEquals(2, result.getItemCount());
    }

    // ==================== 题目 ====================

    @Test
    void addItem_autoIncrementsOrderNo() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(dataset(id, EvaluationDataset.STATUS_DRAFT, 1)));
        when(itemRepository.findMaxOrderNo(id, 1)).thenReturn(4);

        EvaluationItem item = service.addItem(id, itemRequest(), companyId);

        assertEquals(5, item.getOrderNo());
    }

    @Test
    void addItem_respectsExplicitOrderNo() {
        UUID id = UUID.randomUUID();
        when(datasetRepository.findById(id)).thenReturn(Optional.of(dataset(id, EvaluationDataset.STATUS_DRAFT, 1)));
        EvaluationItemRequest req = itemRequest();
        req.setOrderNo(9);

        EvaluationItem item = service.addItem(id, req, companyId);

        assertEquals(9, item.getOrderNo());
    }

    // ==================== 复制公共模板 ====================

    @Test
    void copyPublicDataset_rejectsPrivateSource() {
        UUID sourceId = UUID.randomUUID();
        EvaluationDataset privateDs = dataset(sourceId, EvaluationDataset.STATUS_PUBLISHED, 1);
        privateDs.setCompanyId(companyId);
        when(datasetRepository.findById(sourceId)).thenReturn(Optional.of(privateDs));

        assertThrows(BusinessException.class,
                () -> service.copyPublicDataset(sourceId, UUID.randomUUID()));
        verify(datasetRepository, never()).save(any(EvaluationDataset.class));
    }

    @Test
    void copyPublicDataset_copiesItemsToTargetCompany() {
        UUID sourceId = UUID.randomUUID();
        EvaluationDataset source = dataset(sourceId, EvaluationDataset.STATUS_PUBLISHED, 1);
        source.setCompanyId(null);
        when(datasetRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(versionRepository.findFirstByDatasetIdAndStatusOrderByVersionDesc(
                sourceId, EvaluationDatasetVersion.STATUS_PUBLISHED))
                .thenReturn(Optional.of(publishedVersion(sourceId, 1)));
        EvaluationItem srcItem = EvaluationItem.builder()
                .id(UUID.randomUUID()).datasetId(sourceId).version(1)
                .question("复购率怎么算？").orderNo(3).status(EvaluationItem.STATUS_ACTIVE).build();
        when(itemRepository.findByDatasetIdAndVersionAndStatus(sourceId, 1, EvaluationItem.STATUS_ACTIVE))
                .thenReturn(List.of(srcItem));

        EvaluationDataset copy = service.copyPublicDataset(sourceId, companyId);

        assertNotNull(copy);
        assertEquals(companyId, copy.getCompanyId());
        ArgumentCaptor<EvaluationItem> captor = ArgumentCaptor.forClass(EvaluationItem.class);
        verify(itemRepository).save(captor.capture());
        assertEquals(companyId, captor.getValue().getCompanyId());
        assertEquals(copy.getId(), captor.getValue().getDatasetId());
        assertEquals(1, captor.getValue().getVersion());
        assertEquals("复购率怎么算？", captor.getValue().getQuestion());
        assertEquals(3, captor.getValue().getOrderNo());
    }

    @Test
    void copyPublicDataset_rejectsTemplateNeverPublished() {
        UUID sourceId = UUID.randomUUID();
        EvaluationDataset source = dataset(sourceId, EvaluationDataset.STATUS_DRAFT, 1);
        source.setCompanyId(null);
        when(datasetRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(versionRepository.findFirstByDatasetIdAndStatusOrderByVersionDesc(
                sourceId, EvaluationDatasetVersion.STATUS_PUBLISHED))
                .thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.copyPublicDataset(sourceId, companyId));
        verify(datasetRepository, never()).save(any(EvaluationDataset.class));
    }

    @Test
    void copyPublicDataset_rejectsRetiredTemplate() {
        UUID sourceId = UUID.randomUUID();
        EvaluationDataset source = dataset(sourceId, EvaluationDataset.STATUS_RETIRED, 1);
        source.setCompanyId(null);
        when(datasetRepository.findById(sourceId)).thenReturn(Optional.of(source));

        assertThrows(BusinessException.class, () -> service.copyPublicDataset(sourceId, companyId));
    }

    /** 模板发布 v1 后又开出 v2 草稿：复制必须取 v1 快照，不能把未审核的 v2 题目带出去。 */
    @Test
    void copyPublicDataset_copiesPublishedSnapshotNotNewerDraft() {
        UUID sourceId = UUID.randomUUID();
        EvaluationDataset source = dataset(sourceId, EvaluationDataset.STATUS_DRAFT, 2);
        source.setCompanyId(null);
        when(datasetRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(versionRepository.findFirstByDatasetIdAndStatusOrderByVersionDesc(
                sourceId, EvaluationDatasetVersion.STATUS_PUBLISHED))
                .thenReturn(Optional.of(publishedVersion(sourceId, 1)));
        EvaluationItem publishedItem = EvaluationItem.builder()
                .id(UUID.randomUUID()).datasetId(sourceId).version(1)
                .question("已发布题").status(EvaluationItem.STATUS_ACTIVE).build();
        when(itemRepository.findByDatasetIdAndVersionAndStatus(sourceId, 1, EvaluationItem.STATUS_ACTIVE))
                .thenReturn(List.of(publishedItem));

        service.copyPublicDataset(sourceId, companyId);

        verify(itemRepository).findByDatasetIdAndVersionAndStatus(
                sourceId, 1, EvaluationItem.STATUS_ACTIVE);
        verify(itemRepository, never()).findByDatasetIdAndVersionAndStatus(
                sourceId, 2, EvaluationItem.STATUS_ACTIVE);
    }

    /** super_admin 复制公共模板：目标企业为 null，产出的仍是平台模板。 */
    @Test
    void copyPublicDataset_allowsPlatformTemplateTarget() {
        UUID sourceId = UUID.randomUUID();
        EvaluationDataset source = dataset(sourceId, EvaluationDataset.STATUS_PUBLISHED, 1);
        source.setCompanyId(null);
        when(datasetRepository.findById(sourceId)).thenReturn(Optional.of(source));
        when(versionRepository.findFirstByDatasetIdAndStatusOrderByVersionDesc(
                sourceId, EvaluationDatasetVersion.STATUS_PUBLISHED))
                .thenReturn(Optional.of(publishedVersion(sourceId, 1)));
        when(itemRepository.findByDatasetIdAndVersionAndStatus(sourceId, 1, EvaluationItem.STATUS_ACTIVE))
                .thenReturn(List.of());

        EvaluationDataset copy = service.copyPublicDataset(sourceId, null);

        assertNull(copy.getCompanyId());
    }

    // ==================== 工具 ====================

    private EvaluationDatasetRequest request() {
        return EvaluationDatasetRequest.builder()
                .name("销售FAQ回归")
                .purpose(EvaluationDataset.PURPOSE_REGRESSION)
                .build();
    }

    private EvaluationItemRequest itemRequest() {
        return EvaluationItemRequest.builder()
                .question("复购率怎么算？")
                .expectedPoints("[\"复购率=重复购买客户数/总客户数\"]")
                .build();
    }

    private EvaluationDatasetVersion publishedVersion(UUID datasetId, int version) {
        return EvaluationDatasetVersion.builder()
                .id(UUID.randomUUID()).datasetId(datasetId).version(version)
                .status(EvaluationDatasetVersion.STATUS_PUBLISHED).itemCount(1)
                .frozenAt(LocalDateTime.now()).createdAt(LocalDateTime.now())
                .build();
    }

    // ==================== 题目删除的引用保护 ====================

    /** 被评测运行引用过的题目不得删除：删掉会让历史运行的证据从详情里消失。 */
    @Test
    void deleteItem_referencedByRun_rejected() {
        UUID itemId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(EvaluationItem.builder()
                .id(itemId).datasetId(datasetId).question("q").build()));
        when(datasetRepository.findById(datasetId))
                .thenReturn(Optional.of(dataset(datasetId, EvaluationDataset.STATUS_DRAFT, 1)));
        when(runItemRepository.existsByItemId(itemId)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.deleteItem(itemId));

        assertEquals(409, ex.getErrorCode());
        verify(itemRepository, never()).delete(any());
    }

    /** 未被任何运行引用的题目照常删除。 */
    @Test
    void deleteItem_unreferenced_deletes() {
        UUID itemId = UUID.randomUUID();
        UUID datasetId = UUID.randomUUID();
        EvaluationItem item = EvaluationItem.builder().id(itemId).datasetId(datasetId).question("q").build();
        when(itemRepository.findById(itemId)).thenReturn(Optional.of(item));
        when(datasetRepository.findById(datasetId))
                .thenReturn(Optional.of(dataset(datasetId, EvaluationDataset.STATUS_DRAFT, 1)));
        when(runItemRepository.existsByItemId(itemId)).thenReturn(false);

        service.deleteItem(itemId);

        verify(itemRepository).delete(item);
    }

    private EvaluationDataset dataset(UUID id, String status, int version) {
        return EvaluationDataset.builder()
                .id(id).companyId(null).name("题集")
                .purpose(EvaluationDataset.PURPOSE_REGRESSION)
                .status(status).currentVersion(version).itemCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }
}
