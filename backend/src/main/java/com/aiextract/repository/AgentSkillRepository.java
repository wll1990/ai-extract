package com.aiextract.repository;

import com.aiextract.model.AgentSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentSkillRepository extends JpaRepository<AgentSkill, UUID> {

    /** 列表投影：不加载 body_md。 */
    interface ListRow {
        UUID getId();

        String getSkillKey();

        String getName();

        String getSource();

        String getStatus();

        Integer getVersion();

        UUID getCompanyId();

        Instant getUpdatedAt();
    }

    @Query("""
            select s.id as id, s.skillKey as skillKey, s.name as name, s.source as source,
                   s.status as status, s.version as version, s.companyId as companyId,
                   s.updatedAt as updatedAt
            from AgentSkill s
            where s.companyId is null
              and (:keyword = ''
                   or lower(s.skillKey) like lower(concat('%', :keyword, '%'))
                   or lower(s.name) like lower(concat('%', :keyword, '%')))
              and (:source = '' or s.source = :source)
            order by s.updatedAt desc
            """)
    List<ListRow> listPlatform(@Param("keyword") String keyword, @Param("source") String source);

    @Query("""
            select s.id as id, s.skillKey as skillKey, s.name as name, s.source as source,
                   s.status as status, s.version as version, s.companyId as companyId,
                   s.updatedAt as updatedAt
            from AgentSkill s
            where s.companyId = :companyId
              and (:keyword = ''
                   or lower(s.skillKey) like lower(concat('%', :keyword, '%'))
                   or lower(s.name) like lower(concat('%', :keyword, '%')))
              and (:source = '' or s.source = :source)
            order by s.updatedAt desc
            """)
    List<ListRow> listTenant(@Param("companyId") UUID companyId,
                             @Param("keyword") String keyword,
                             @Param("source") String source);

    /** 目录投影：只取三段文本，不加载正文。 */
    interface CatalogRow {
        String getSkillKey();

        String getName();

        String getDescription();
    }

    @Query("""
            select s.skillKey as skillKey, s.name as name, s.description as description
            from AgentSkill s
            where s.companyId is null and s.status = 'published'
            order by s.skillKey asc
            """)
    List<CatalogRow> catalogPlatform();

    @Query("""
            select s.skillKey as skillKey, s.name as name, s.description as description
            from AgentSkill s
            where s.companyId = :companyId and s.status = 'published'
            order by s.skillKey asc
            """)
    List<CatalogRow> catalogTenant(@Param("companyId") UUID companyId);

    Optional<AgentSkill> findFirstByCompanyIdIsNullAndSkillKeyAndStatusOrderByVersionDesc(
            String skillKey, String status);

    Optional<AgentSkill> findFirstByCompanyIdAndSkillKeyAndStatusOrderByVersionDesc(
            UUID companyId, String skillKey, String status);

    List<AgentSkill> findByCompanyIdIsNullAndSkillKeyOrderByVersionDesc(String skillKey);

    List<AgentSkill> findByCompanyIdAndSkillKeyOrderByVersionDesc(UUID companyId, String skillKey);

    boolean existsByCompanyIdIsNullAndSkillKey(String skillKey);

    boolean existsByCompanyIdAndSkillKey(UUID companyId, String skillKey);

    @Query("select coalesce(max(s.version), 0) from AgentSkill s where s.companyId is null and s.skillKey = :key")
    int maxPlatformVersion(@Param("key") String key);

    @Query("select coalesce(max(s.version), 0) from AgentSkill s where s.companyId = :companyId and s.skillKey = :key")
    int maxTenantVersion(@Param("companyId") UUID companyId, @Param("key") String key);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AgentSkill s set s.status = 'archived', s.archivedAt = :now, s.archivedBy = :actor "
            + "where s.companyId = :companyId and s.skillKey = :key and s.status = 'published'")
    int archiveTenantPublished(@Param("companyId") UUID companyId, @Param("key") String key,
                               @Param("now") Instant now, @Param("actor") UUID actor);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AgentSkill s set s.status = 'archived', s.archivedAt = :now, s.archivedBy = :actor "
            + "where s.companyId is null and s.skillKey = :key and s.status = 'published'")
    int archivePlatformPublished(@Param("key") String key, @Param("now") Instant now,
                                 @Param("actor") UUID actor);

    /** 每个 (scope, key) 只保留最近 10 个 archived 版本。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM public.agent_skill s WHERE s.status = 'archived' AND ("
            + "SELECT count(*) FROM public.agent_skill x WHERE x.status = 'archived' "
            + "AND x.skill_key = s.skill_key "
            + "AND ((x.company_id IS NULL AND s.company_id IS NULL) OR x.company_id = s.company_id) "
            + "AND x.version > s.version) >= 10", nativeQuery = true)
    int deleteOldArchivedVersions();
}
