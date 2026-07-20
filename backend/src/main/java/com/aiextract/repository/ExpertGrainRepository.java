package com.aiextract.repository;

import com.aiextract.model.ExpertGrain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * @author AI Extract Team
 */
@Repository
public interface ExpertGrainRepository extends JpaRepository<ExpertGrain, UUID> {
    /**
     * 查询，按，expert，id。
     * @param expertId 参数
     * @return 查询结果列表
     */
    List<ExpertGrain> findByExpertId(UUID expertId);
    /**
     * 查询（Expert,Id,Status）。
     * @param expertId expertId
     * @param status status
     * @return 结果列表
     */
    List<ExpertGrain> findByExpertIdAndStatus(UUID expertId, String status);
    /**
     * 查询（Expert,Id,Status）。
     * @param expertIds expertIds
     * @param status status
     * @return 结果列表
     */
    List<ExpertGrain> findByExpertIdInAndStatus(List<UUID> expertIds, String status);
    /**
     * 删除（Expert,Id）。
     * @param expertId expertId
     */
    void deleteByExpertId(UUID expertId);
    @Query("SELECT eg.expertId, COUNT(eg) FROM ExpertGrain eg WHERE eg.expertId IN :ids GROUP BY eg.expertId")
    /**
     * 统计（Expert,Id）。
     * @param ids ids
     * @return 结果列表
     */
    List<Object[]> countByExpertIdIn(@Param("ids") List<UUID> ids);


}
