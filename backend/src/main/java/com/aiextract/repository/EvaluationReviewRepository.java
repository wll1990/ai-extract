package com.aiextract.repository;

import com.aiextract.model.EvaluationReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 评测人工复核数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface EvaluationReviewRepository extends JpaRepository<EvaluationReview, UUID> {

    /** 单题复核历史（追加式，按时间升序还原改分过程） */
    List<EvaluationReview> findByRunItemIdOrderByCreatedAtAsc(UUID runItemId);

    /** 批量取复核记录（详情页一次加载整批，避免 N+1） */
    List<EvaluationReview> findByRunItemIdInOrderByCreatedAtAsc(Collection<UUID> runItemIds);

    /** 是否已有人工复核记录（定稿前的必审项检查） */
    boolean existsByRunItemId(UUID runItemId);
}
