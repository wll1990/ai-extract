package com.aiextract.repository;

import com.aiextract.model.ReportHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

/**
 * @author AI Extract Team
 */
public interface ReportHistoryRepository extends JpaRepository<ReportHistory, UUID> {
    /**
     * 查询，按，skill，id，排序，按，generated，at，降序。
     * @param skillId 参数
     * @return 查询结果列表
     */
    List<ReportHistory> findBySkillIdOrderByGeneratedAtDesc(UUID skillId);
}
