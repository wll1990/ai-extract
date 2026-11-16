package com.aiextract.repository;

import com.aiextract.model.EvaluationDataset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 题集数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Repository
public interface EvaluationDatasetRepository extends JpaRepository<EvaluationDataset, UUID> {

    /**
     * 分页检索题集 —— 单条 JPQL，无 N+1。
     *
     * <p>scopeAll=true 不过滤企业（super_admin 看全部），否则过滤「本企业 + 平台公共」。
     * keyword 只搜 name（varchar），不搜 description（TEXT）。</p>
     */
    @Query("SELECT d FROM EvaluationDataset d WHERE " +
            "(:scopeAll = true OR d.companyId = :companyId OR d.companyId IS NULL) AND " +
            "(:purpose IS NULL OR d.purpose = :purpose) AND " +
            "(:status IS NULL OR d.status = :status) AND " +
            "(:keyword IS NULL OR d.name LIKE CONCAT('%', CAST(:keyword AS string), '%')) " +
            "ORDER BY d.createdAt DESC, d.id DESC")
    Page<EvaluationDataset> search(
            @Param("scopeAll") boolean scopeAll,
            @Param("companyId") UUID companyId,
            @Param("purpose") String purpose,
            @Param("status") String status,
            @Param("keyword") String keyword,
            Pageable pageable);
}
