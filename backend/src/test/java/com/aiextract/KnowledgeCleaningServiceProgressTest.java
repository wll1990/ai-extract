package com.aiextract;

import com.aiextract.model.KnowledgeEntry;
import com.aiextract.model.KnowledgeUploadDraft;
import com.aiextract.model.KnowledgeUploadTask;
import com.aiextract.repository.KnowledgeEntryRepository;
import com.aiextract.repository.KnowledgeUploadTaskRepository;
import com.aiextract.service.KnowledgeCleaningService;
import com.aiextract.service.KnowledgeCleaningService.KnowledgeCandidate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识上传章节级进度 / 断点续传单元测试 —— commitSection 检查点原子性、markTaskDone 空文档判定。
 * 只 mock 两个仓储，其余依赖传 null（被 mock 方法不触达）。
 */
class KnowledgeCleaningServiceProgressTest {

    private final KnowledgeEntryRepository knowledgeRepository = mock(KnowledgeEntryRepository.class);
    private final KnowledgeUploadTaskRepository uploadTaskRepository = mock(KnowledgeUploadTaskRepository.class);
    private final com.aiextract.repository.KnowledgeUploadDraftRepository draftRepository =
            mock(com.aiextract.repository.KnowledgeUploadDraftRepository.class);

    private final KnowledgeCleaningService svc = new KnowledgeCleaningService(
            knowledgeRepository, uploadTaskRepository, draftRepository, null, null, null, null, null, null);

    @Test
    void commitSection_accumulatesProducedCount_andAdvancesCheckpoint() {
        UUID taskId = UUID.randomUUID();
        KnowledgeUploadTask task = KnowledgeUploadTask.builder()
                .id(taskId).producedCount(2).totalSections(5).processedSections(1).build();
        when(uploadTaskRepository.findById(taskId)).thenReturn(Optional.of(task));

        KnowledgeCandidate c = new KnowledgeCandidate("BANT 四问法", "methodology", "预算/权限/需求/时间", "第1章");
        int added = svc.commitSection(taskId, UUID.randomUUID(), null, List.of(c), 2, 0, false, null);

        assertEquals(1, added);
        assertEquals(3, task.getProducedCount(), "producedCount 应累加而非覆盖（断点续传关键）");
        assertEquals(2, task.getProcessedSections(), "检查点应前移到本节索引");
        verify(draftRepository).save(any());
    }

    @Test
    void commitSection_sourceIncludesUploadedFileName() {
        UUID taskId = UUID.randomUUID();
        KnowledgeUploadTask task = KnowledgeUploadTask.builder()
                .id(taskId).fileName("产品手册.pdf").producedCount(0)
                .totalSections(1).processedSections(0).build();
        when(uploadTaskRepository.findById(taskId)).thenReturn(Optional.of(task));

        KnowledgeCandidate c = new KnowledgeCandidate("BANT 四问法", "methodology",
                "预算、权限、需求、时间四个问题，先确认决策链再推进。", "第1章 需求挖掘");
        svc.commitSection(taskId, UUID.randomUUID(), null, List.of(c), 1, 0, false, null);

        ArgumentCaptor<KnowledgeUploadDraft> captor = ArgumentCaptor.forClass(KnowledgeUploadDraft.class);
        verify(draftRepository).save(captor.capture());
        assertEquals("《产品手册.pdf》 · 第1章 需求挖掘", captor.getValue().getSource(),
                "普通文档上传也必须带文件名，否则无法追溯原文");
    }

    @Test
    void adoptDrafts_writesSourceTaskAndDocument() {
        UUID taskId = UUID.randomUUID();
        KnowledgeUploadTask task = KnowledgeUploadTask.builder()
                .id(taskId).companyId(UUID.randomUUID()).fileName("产品手册.pdf")
                .sourceKind("internal").build();
        KnowledgeUploadDraft draft = KnowledgeUploadDraft.builder()
                .id(UUID.randomUUID()).taskId(taskId).sectionNo(1)
                .title("BANT 四问法").content("预算、权限、需求、时间四个问题，先确认决策链再推进。")
                .category("methodology").source("《产品手册.pdf》 · 第1章")
                .status(KnowledgeUploadDraft.STATUS_PENDING).build();
        when(uploadTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(draftRepository.findByTaskIdAndStatus(taskId, KnowledgeUploadDraft.STATUS_PENDING))
                .thenReturn(List.of(draft));

        var adopted = svc.adoptDrafts(taskId, null, UUID.randomUUID());

        assertEquals(1, adopted.adopted());
        assertEquals(0, adopted.skipped().size());
        ArgumentCaptor<KnowledgeEntry> captor = ArgumentCaptor.forClass(KnowledgeEntry.class);
        verify(knowledgeRepository).save(captor.capture());
        assertEquals(taskId, captor.getValue().getSourceTaskId());
        assertEquals("产品手册.pdf", captor.getValue().getSourceDocument());
    }

    @Test
    void adoptDrafts_bookMode_usesBookTitleAsDocument() {
        UUID taskId = UUID.randomUUID();
        KnowledgeUploadTask task = KnowledgeUploadTask.builder()
                .id(taskId).companyId(UUID.randomUUID()).fileName("销售洗脑.pdf")
                .sourceKind("book").build();
        KnowledgeUploadDraft draft = KnowledgeUploadDraft.builder()
                .id(UUID.randomUUID()).taskId(taskId).sectionNo(1)
                .title("先建立信任再谈产品").content("客户先信人再信产品，先做信任资产盘点。")
                .category("methodology").source("《销售洗脑》 · 第2章")
                .status(KnowledgeUploadDraft.STATUS_PENDING).build();
        when(uploadTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(draftRepository.findByTaskIdAndStatus(taskId, KnowledgeUploadDraft.STATUS_PENDING))
                .thenReturn(List.of(draft));

        svc.adoptDrafts(taskId, null, UUID.randomUUID());

        ArgumentCaptor<KnowledgeEntry> captor = ArgumentCaptor.forClass(KnowledgeEntry.class);
        verify(knowledgeRepository).save(captor.capture());
        assertEquals("销售洗脑", captor.getValue().getSourceDocument(), "书籍模式来源文档取书名而不是文件名");
    }

    @Test
    void adoptDrafts_qualityBlocked_returnsSkippedReason() {
        UUID taskId = UUID.randomUUID();
        KnowledgeUploadTask task = KnowledgeUploadTask.builder()
                .id(taskId).companyId(UUID.randomUUID()).fileName("手册.pdf")
                .sourceKind("internal").build();
        KnowledgeUploadDraft draft = KnowledgeUploadDraft.builder()
                .id(UUID.randomUUID()).taskId(taskId).sectionNo(1)
                .title("版权页残留").content("版权所有，翻印必究")
                .category("methodology").status(KnowledgeUploadDraft.STATUS_PENDING)
                .qualityIssues("疑似版权页/目录，不是可检索的知识").build();
        when(uploadTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(draftRepository.findByTaskIdAndStatus(taskId, KnowledgeUploadDraft.STATUS_PENDING))
                .thenReturn(List.of(draft));

        var result = svc.adoptDrafts(taskId, null, UUID.randomUUID());

        assertEquals(0, result.adopted());
        assertEquals(1, result.skipped().size(), "未达标草稿必须返回跳过原因而不是静默少采纳");
        assertEquals("版权页残留", result.skipped().get(0).get("title"));
        verify(knowledgeRepository, never()).save(any());
    }

    @Test
    void commitSection_skipsInvalidCategory() {
        UUID taskId = UUID.randomUUID();
        KnowledgeUploadTask task = KnowledgeUploadTask.builder()
                .id(taskId).producedCount(0).totalSections(2).processedSections(0).build();
        when(uploadTaskRepository.findById(taskId)).thenReturn(Optional.of(task));

        KnowledgeCandidate bad = new KnowledgeCandidate("非法分类", "not_a_category", "内容", "第1章");
        int added = svc.commitSection(taskId, UUID.randomUUID(), null, List.of(bad), 1, 0, false, null);

        assertEquals(0, added);
        assertEquals(0, task.getProducedCount());
        assertEquals(1, task.getProcessedSections(), "非法候选跳过，但检查点仍前移");
    }

    @Test
    void markTaskDone_emptyDocument_marksFailed() {
        UUID taskId = UUID.randomUUID();
        KnowledgeUploadTask task = KnowledgeUploadTask.builder()
                .id(taskId).status(KnowledgeUploadTask.STATUS_EXTRACTING).producedCount(0).build();
        when(uploadTaskRepository.findById(taskId)).thenReturn(Optional.of(task));

        svc.markTaskDone(taskId);

        assertEquals(KnowledgeUploadTask.STATUS_FAILED, task.getStatus(), "全文档无候选应记为失败");
    }

    @Test
    void markTaskDone_withCandidates_marksDone() {
        UUID taskId = UUID.randomUUID();
        KnowledgeUploadTask task = KnowledgeUploadTask.builder()
                .id(taskId).status(KnowledgeUploadTask.STATUS_EXTRACTING).producedCount(7).build();
        when(uploadTaskRepository.findById(taskId)).thenReturn(Optional.of(task));

        svc.markTaskDone(taskId);

        assertEquals(KnowledgeUploadTask.STATUS_DONE, task.getStatus());
    }
}
