package com.aiextract.repository;

import com.aiextract.model.CandidateGrain;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 候选颗粒 Repository。
  * @author AI Extract Team
 */
@Repository
public interface CandidateGrainRepository extends JpaRepository<CandidateGrain, UUID> {
    /**
     * 查询By Skill Id And Status Order By Created At Desc。
     * @param skillId 参数
     * @param status 参数
     * @return 结果列表
     */
    List<CandidateGrain> findBySkillIdAndStatusOrderByCreatedAtDesc(UUID skillId, String status, Pageable pageable);
    /**
     * 查询（Status,Created,At）。
     * @param status status
     * @return 结果列表
     */
    List<CandidateGrain> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    /**
     * 查询（Source,Insight,Id）。
     * @param insightId insightId
     * @return 结果列表
     */
    List<CandidateGrain> findBySourceInsightId(UUID insightId);
    /**
     * 统计（Skill,Id,Status）。
     * @param skillId skillId
     * @param status status
     * @return 统计数量
     */
    long countBySkillIdAndStatus(UUID skillId, String status);
    /**
     * 统计（Status）。
     * @param status status
     * @return 统计数量
     */
    long countByStatus(String status);


}
