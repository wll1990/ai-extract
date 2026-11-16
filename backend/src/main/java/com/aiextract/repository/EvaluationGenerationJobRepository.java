package com.aiextract.repository;

import com.aiextract.model.EvaluationGenerationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AI 生成候选任务数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Repository
public interface EvaluationGenerationJobRepository extends JpaRepository<EvaluationGenerationJob, UUID> {

    /** 题集最近一条指定状态的任务（用于「同一题集同一时间仅一个 running」防重复点击）。 */
    Optional<EvaluationGenerationJob> findFirstByDatasetIdAndStatusOrderByCreatedAtDesc(UUID datasetId, String status);

    /** 题集最近的生成任务（离开页面后回来还能看到进度/结果） */
    List<EvaluationGenerationJob> findTop20ByDatasetIdOrderByCreatedAtDesc(UUID datasetId);
}
