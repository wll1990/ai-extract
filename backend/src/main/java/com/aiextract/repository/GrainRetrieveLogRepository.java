package com.aiextract.repository;

import com.aiextract.model.GrainRetrieveLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * RAG 检索日志数据访问接口。
  * @author AI Extract Team
 */
@Repository
public interface GrainRetrieveLogRepository extends JpaRepository<GrainRetrieveLog, UUID> {
    /**
     * 统计（Skill,Id,Group,Scene,Tag）。
     * @param skillId skillId
     * @return 结果列表
     */
    @Query("SELECT grl.sceneTag, COUNT(grl) FROM GrainRetrieveLog grl WHERE grl.skillId = :skillId GROUP BY grl.sceneTag")
    List<Object[]> countBySkillIdGroupBySceneTag(@Param("skillId") UUID skillId);
    /**
     * 删除（Older,Than30,Days）。
     * @return 统计数量
     */
    @Modifying
    @Query(value = "DELETE FROM grain_retrieve_log WHERE created_at < NOW() - INTERVAL '30 days'", nativeQuery = true)
    int deleteOlderThan30Days();

    /** 按颗粒 ID 查询检索历史，按时间降序 */
    List<GrainRetrieveLog> findByGrainIdOrderByCreatedAtDesc(UUID grainId, Pageable pageable);

}
