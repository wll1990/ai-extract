package com.aiextract.repository;

import com.aiextract.model.EvaluationDatasetVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 题集版本数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Repository
public interface EvaluationDatasetVersionRepository extends JpaRepository<EvaluationDatasetVersion, UUID> {

    /** 按题集+版本号精确查（UNIQUE(dataset_id, version)） */
    Optional<EvaluationDatasetVersion> findByDatasetIdAndVersion(UUID datasetId, Integer version);

    /** 题集下所有版本，按版本号降序 */
    List<EvaluationDatasetVersion> findByDatasetIdOrderByVersionDesc(UUID datasetId);

    /** 题集下最新一条指定状态的版本（如最新 published 版） */
    Optional<EvaluationDatasetVersion> findFirstByDatasetIdAndStatusOrderByVersionDesc(UUID datasetId, String status);
}
