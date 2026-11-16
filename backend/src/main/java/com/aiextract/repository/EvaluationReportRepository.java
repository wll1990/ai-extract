package com.aiextract.repository;

import com.aiextract.model.EvaluationReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 评测验收报告数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface EvaluationReportRepository extends JpaRepository<EvaluationReport, UUID> {

    /** 当前最新报告（版本倒序取首条） */
    Optional<EvaluationReport> findFirstByRunIdOrderByVersionDesc(UUID runId);

    /** 报告版本历史（修订链） */
    List<EvaluationReport> findByRunIdOrderByVersionDesc(UUID runId);

    /** 指定版本报告 */
    Optional<EvaluationReport> findByRunIdAndVersion(UUID runId, Integer version);

    /** 版本号占用检查（同批次唯一索引兜底前的友好校验） */
    boolean existsByRunIdAndVersion(UUID runId, Integer version);
}
