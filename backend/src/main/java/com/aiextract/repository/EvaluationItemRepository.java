package com.aiextract.repository;

import com.aiextract.model.EvaluationItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 评测题目数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Repository
public interface EvaluationItemRepository extends JpaRepository<EvaluationItem, UUID> {

    /** 题集某版本下题目，按题序分页（编辑页加载） */
    Page<EvaluationItem> findByDatasetIdAndVersionOrderByOrderNoAsc(UUID datasetId, Integer version, Pageable pageable);

    /** 题集某版本下指定状态的题目（运行/考试抽题用 active） */
    List<EvaluationItem> findByDatasetIdAndVersionAndStatus(UUID datasetId, Integer version, String status);

    /** 题集某版本下题目总数 */
    long countByDatasetIdAndVersion(UUID datasetId, Integer version);

    /** 题集某版本下指定状态的题目数（考试发布校验用，必须与抽题口径一致） */
    long countByDatasetIdAndVersionAndStatus(UUID datasetId, Integer version, String status);

    /** AI 草稿审核队列（可按题集过滤） */
    Page<EvaluationItem> findByCompanyIdAndStatusAndGenerationJobIdIsNotNullOrderByCreatedAtDesc(
            UUID companyId, String status, Pageable pageable);

    Page<EvaluationItem> findByCompanyIdAndDatasetIdAndStatusAndGenerationJobIdIsNotNullOrderByCreatedAtDesc(
            UUID companyId, UUID datasetId, String status, Pageable pageable);

    /** 题集某版本下最大题序（无题目返回 0），新增题目自动递增用 */
    @Query("SELECT COALESCE(MAX(i.orderNo), 0) FROM EvaluationItem i WHERE i.datasetId = :datasetId AND i.version = :version")
    int findMaxOrderNo(@Param("datasetId") UUID datasetId, @Param("version") Integer version);

    /**
     * 题集某版本下已有题干（AI 生成候选去重用）。
     *
     * <p>只投影 question 一列：题目带 5 个 JSONB 字段，整行加载去重会白读大量正文。
     * 按题序返回保证结果稳定（同一题集连续两次调用顺序一致，便于断言与 prompt 复用）。</p>
     */
    @Query("SELECT i.question FROM EvaluationItem i WHERE i.datasetId = :datasetId AND i.version = :version "
            + "AND i.status = :status ORDER BY i.orderNo ASC")
    List<String> findQuestionsByDatasetIdAndVersionAndStatus(@Param("datasetId") UUID datasetId,
                                                             @Param("version") Integer version,
                                                             @Param("status") String status);

    /** 多状态题干（AI 生成去重要同时避开 active 与 draft） */
    @Query("SELECT i.question FROM EvaluationItem i WHERE i.datasetId = :datasetId AND i.version = :version "
            + "AND i.status IN :statuses ORDER BY i.orderNo ASC")
    List<String> findQuestionsByDatasetIdAndVersionAndStatusIn(@Param("datasetId") UUID datasetId,
                                                               @Param("version") Integer version,
                                                               @Param("statuses") List<String> statuses);

    /**
     * 题集某版本已有事实标识（事实级去重基线）。
     *
     * <p>只投影 fact_key 一列：题目带 5 个 JSONB 字段，整行加载去重会白读大量正文。
     * 查询走 partial 索引 {@code idx_item_ds_fact}。</p>
     */
    @Query("SELECT i.factKey FROM EvaluationItem i WHERE i.datasetId = :datasetId AND i.version = :version "
            + "AND i.status = :status AND i.factKey IS NOT NULL")
    List<String> findFactKeysByDatasetIdAndVersionAndStatus(@Param("datasetId") UUID datasetId,
                                                            @Param("version") Integer version,
                                                            @Param("status") String status);

    /** 题集某版本已有事实原文（喂 prompt 避让），按题序稳定返回。 */
    @Query("SELECT i.factQuote FROM EvaluationItem i WHERE i.datasetId = :datasetId AND i.version = :version "
            + "AND i.status = :status AND i.factQuote IS NOT NULL ORDER BY i.orderNo ASC")
    List<String> findFactQuotesByDatasetIdAndVersionAndStatus(@Param("datasetId") UUID datasetId,
                                                              @Param("version") Integer version,
                                                              @Param("status") String status);
}
