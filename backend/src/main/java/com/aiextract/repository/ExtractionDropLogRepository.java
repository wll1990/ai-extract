package com.aiextract.repository;

import com.aiextract.model.ExtractionDropLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * @author AI Extract Team
 */
public interface ExtractionDropLogRepository extends JpaRepository<ExtractionDropLog, UUID> {
    /**
     * 查询By Material Id Order By Created At Asc。
     * @param materialId 参数
     * @return 结果列表
     */
    List<ExtractionDropLog> findByMaterialIdOrderByCreatedAtAsc(UUID materialId);
}
