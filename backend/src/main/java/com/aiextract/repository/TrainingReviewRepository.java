package com.aiextract.repository;

import com.aiextract.model.TrainingReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 培训复核记录数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface TrainingReviewRepository extends JpaRepository<TrainingReview, UUID> {

    /** 单次尝试的复核历史（按时间正序，最新结论在最后） */
    List<TrainingReview> findByAttemptIdOrderByCreatedAtAsc(UUID attemptId);

    /** 批量预取多次尝试的复核记录（批次详情避免 N+1） */
    List<TrainingReview> findByAttemptIdInOrderByCreatedAtAsc(List<UUID> attemptIds);
}
