package com.aiextract.repository;

import com.aiextract.model.KnowledgeEntry;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AI 草稿采纳指标（只读）。
 *
 * <p>三张业务表都有 generation_job_id / created_at / status / reviewed_at，
 * 一条 UNION ALL 查询按类型返回 created/approved/discarded/平均审核秒数。</p>
 */
@org.springframework.stereotype.Repository
public interface ContentMetricsRepository extends Repository<KnowledgeEntry, UUID> {

    @Query(value = """
            SELECT 'knowledge' AS kind,
                   count(*) FILTER (WHERE created_at >= :since) AS created,
                   count(*) FILTER (WHERE created_at >= :since AND review_outcome = 'approved') AS approved,
                   count(*) FILTER (WHERE created_at >= :since AND review_outcome = 'rejected') AS discarded,
                   avg(extract(epoch FROM (reviewed_at - created_at)))
                       FILTER (WHERE reviewed_at IS NOT NULL AND created_at >= :since) AS avg_latency
              FROM knowledge_entry
             WHERE company_id = :companyId AND generation_job_id IS NOT NULL
            UNION ALL
            SELECT 'case',
                   count(*) FILTER (WHERE created_at >= :since),
                   count(*) FILTER (WHERE created_at >= :since AND review_outcome = 'approved'),
                   count(*) FILTER (WHERE created_at >= :since AND review_outcome = 'rejected'),
                   avg(extract(epoch FROM (reviewed_at - created_at)))
                       FILTER (WHERE reviewed_at IS NOT NULL AND created_at >= :since)
              FROM industry_case
             WHERE company_id = :companyId AND generation_job_id IS NOT NULL
            UNION ALL
            SELECT 'eval_item',
                   count(*) FILTER (WHERE created_at >= :since),
                   count(*) FILTER (WHERE created_at >= :since AND review_outcome = 'approved'),
                   count(*) FILTER (WHERE created_at >= :since AND review_outcome = 'rejected'),
                   avg(extract(epoch FROM (reviewed_at - created_at)))
                       FILTER (WHERE reviewed_at IS NOT NULL AND created_at >= :since)
              FROM evaluation_item
             WHERE company_id = :companyId AND generation_job_id IS NOT NULL
            """, nativeQuery = true)
    List<Object[]> adoptionRows(@Param("companyId") UUID companyId,
                                @Param("since") LocalDateTime since);
}
