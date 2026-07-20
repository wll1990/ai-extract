package com.aiextract.repository;

import com.aiextract.model.GrainRetrieveLog;
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
    List<Object[]> countBySkillIdGroupBySceneTag(@Param("skillId") UUID skillId);
    /**
     * 删除（Older,Than30,Days）。
     * @return 统计数量
     */
    int deleteOlderThan30Days();


}
