package com.aiextract.service;

import com.aiextract.model.GrainRefineDraft;
import com.aiextract.repository.GrainRefineDraftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 纠偏后的向量重建 —— 跨 Bean 异步，状态回写到草稿。
 *
 * <p>为什么单独一个 Bean：{@code @Async} 在同一个 Bean 内自调用不生效，
 * 而且不能在 applyTx 的事务里调用（会读到未提交的字段）。</p>
 *
 * @author AI Extract Team
 * @since 2026-11-18
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrainEmbeddingRefresher {

    private final AdminGrainService adminGrainService;
    private final GrainRefineDraftRepository draftRepository;

    /**
     * 重建颗粒向量并回写 embedding_status。
     *
     * <p>调用方必须在事务提交之后调用；成功后前端才点亮「用这个问题再问一次」。</p>
     */
    @Async("embeddingExecutor")
    public void refreshAsync(UUID grainId, UUID draftId) {
        try {
            adminGrainService.refreshEmbedding(grainId);
            draftRepository.updateEmbeddingStatus(draftId, GrainRefineDraft.EMBEDDING_READY);
            log.info("纠偏向量重建完成 grainId={} draftId={}", grainId, draftId);
        } catch (Exception e) {
            log.warn("纠偏向量重建失败 grainId={} draftId={}: {}", grainId, draftId, e.getMessage());
            try {
                draftRepository.updateEmbeddingStatus(draftId, GrainRefineDraft.EMBEDDING_FAILED);
            } catch (Exception updateError) {
                log.warn("回写纠偏向量失败状态也失败 draftId={}: {}", draftId, updateError.getMessage());
            }
        }
    }
}
