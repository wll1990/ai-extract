package com.aiextract.repository;

import com.aiextract.model.AnalyticsEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * 前端埋点数据访问接口。
  * @author AI Extract Team
 */
@Repository
public interface AnalyticsEventRepository extends JpaRepository<AnalyticsEvent, UUID> {

    /** 清理 30 天前的埋点数据。事务由调用方 Service/Scheduler 管理。 */
    @Modifying
    @Query(value = "DELETE FROM analytics_event WHERE created_at < NOW() - INTERVAL '30 days'", nativeQuery = true)
    /**
     * 删除Older Than30 Days。
     * @return 统计值
     */
    int deleteOlderThan30Days();
}
