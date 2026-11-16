package com.aiextract.repository;

import com.aiextract.model.KnowledgeCaseRetrieveLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 知识/案例召回日志数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-11-13
 */
@Repository
public interface KnowledgeCaseRetrieveLogRepository extends JpaRepository<KnowledgeCaseRetrieveLog, UUID> {

    /**
     * 幂等写入：dedupe_key 已存在时静默跳过。
     *
     * <p>用于评测/培训阅读的消费回流——同一题/同一天重复打开不能把「被引用」刷成虚假热度。</p>
     */
    @Modifying
    @Query(value = """
            INSERT INTO knowledge_case_retrieve_log
                (id, knowledge_id, case_id, skill_id, company_id, usage_type,
                 usage_ref_id, conversation_id, similarity, dedupe_key, created_at)
            VALUES (:id, :knowledgeId, :caseId, :skillId, :companyId, :usageType,
                    :usageRefId, NULL, :similarity, :dedupeKey, :createdAt)
            ON CONFLICT (dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING
            """, nativeQuery = true)
    int insertIgnore(@Param("id") UUID id,
                     @Param("knowledgeId") UUID knowledgeId,
                     @Param("caseId") UUID caseId,
                     @Param("skillId") UUID skillId,
                     @Param("companyId") UUID companyId,
                     @Param("usageType") String usageType,
                     @Param("usageRefId") UUID usageRefId,
                     @Param("similarity") Double similarity,
                     @Param("dedupeKey") String dedupeKey,
                     @Param("createdAt") java.time.LocalDateTime createdAt);

    /** 每天清理 30 天前的召回日志，避免无限增长。 */
    @Modifying
    @Query(value = "DELETE FROM knowledge_case_retrieve_log WHERE created_at < NOW() - INTERVAL '30 days'",
            nativeQuery = true)
    int deleteOlderThan30Days();

    /** 知识「被引用」聚合：分身名称 + 次数 + 最近引用时间。 */
    @Query(value = """
            SELECT r.skill_id,
                   COALESCE(s.display_name, s.owner_name) AS skill_name,
                   COUNT(*) AS ref_count,
                   MAX(r.created_at) AS last_at
              FROM knowledge_case_retrieve_log r
              LEFT JOIN skill s ON s.id = r.skill_id
             WHERE r.knowledge_id = :id
               AND (:companyId IS NULL OR r.company_id = :companyId)
               AND r.created_at > now() - make_interval(days => :days)
             GROUP BY r.skill_id, s.display_name, s.owner_name
             ORDER BY ref_count DESC, last_at DESC
             LIMIT 20
            """, nativeQuery = true)
    List<Object[]> aggregateKnowledgeReferences(@Param("id") UUID id,
                                                @Param("days") int days,
                                                @Param("companyId") UUID companyId);

    /** 案例「被引用」聚合。 */
    @Query(value = """
            SELECT r.skill_id,
                   COALESCE(s.display_name, s.owner_name) AS skill_name,
                   COUNT(*) AS ref_count,
                   MAX(r.created_at) AS last_at
              FROM knowledge_case_retrieve_log r
              LEFT JOIN skill s ON s.id = r.skill_id
             WHERE r.case_id = :id
               AND (:companyId IS NULL OR r.company_id = :companyId)
               AND r.created_at > now() - make_interval(days => :days)
             GROUP BY r.skill_id, s.display_name, s.owner_name
             ORDER BY ref_count DESC, last_at DESC
             LIMIT 20
            """, nativeQuery = true)
    List<Object[]> aggregateCaseReferences(@Param("id") UUID id,
                                           @Param("days") int days,
                                           @Param("companyId") UUID companyId);
}
