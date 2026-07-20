package com.aiextract.repository;

import com.aiextract.model.GrainEditHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * @author AI Extract Team
 */
@Repository
public interface GrainEditHistoryRepository extends JpaRepository<GrainEditHistory, UUID> {
    /**
     * 查询，按，grain，id，排序，按，created，at，降序。
     * @param grainId 参数
     * @return 查询结果列表
     */
    List<GrainEditHistory> findByGrainIdOrderByCreatedAtDesc(UUID grainId);
}
