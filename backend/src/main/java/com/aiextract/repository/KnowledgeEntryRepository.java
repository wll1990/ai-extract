package com.aiextract.repository;

import com.aiextract.model.KnowledgeEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 知识库条目数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Repository
public interface KnowledgeEntryRepository extends JpaRepository<KnowledgeEntry, UUID> {

    /** 同企业同标题的待审草稿：共性问题重复点击时复用，避免造重复培训主题 */
    java.util.Optional<KnowledgeEntry> findFirstByCompanyIdAndTitleAndStatusOrderByCreatedAtDesc(
            UUID companyId, String title, String status);

    /** 同一版本链下指定状态的条目，用于保证同 root 只有一个 active */
    List<KnowledgeEntry> findByRootIdAndStatus(UUID rootId, String status);

    /** 员工「我的提交」 */
    Page<KnowledgeEntry> findByCompanyIdAndCreatedByOrderByCreatedAtDesc(
            UUID companyId, UUID createdBy, Pageable pageable);

    /** 批量取标题等轻量场景：SQL 层过滤企业，跨企业条目不会查出来 */
    @Query("SELECT k FROM KnowledgeEntry k WHERE k.id IN :ids "
            + "AND (k.companyId = :companyId OR k.companyId IS NULL)")
    List<KnowledgeEntry> findVisibleByIdIn(@Param("ids") java.util.Collection<UUID> ids,
                                           @Param("companyId") UUID companyId);

    /** 向量化失败/待索引且到达重试时间的条目 */
    @Query("SELECT k.id FROM KnowledgeEntry k WHERE k.status = 'active' "
            + "AND k.embeddingStatus IN ('pending','failed') "
            + "AND (k.embeddingNextRetryAt IS NULL OR k.embeddingNextRetryAt <= :now) "
            + "ORDER BY k.updatedAt ASC")
    List<UUID> findEmbeddingRetryIds(@Param("now") java.time.LocalDateTime now, Pageable pageable);

    /**
     * 分页检索知识条目 — 单条 JPQL，无 N+1。
     *
     * <p>scopeAll=true 时不过滤企业（super_admin 看全部），否则过滤「本企业 + 平台公共」
     * （company_id = :companyId OR company_id IS NULL）。scope 可选：public=仅公共、
     * private=仅私有（company_id 非空）。keyword 同时匹配标题与正文（知识条目量级小，
     * 可接受 LIKE 扫描；后续数据量上来再加 pg_trgm 索引）。</p>
     *
     * <p>过期态由 expire_at 派生：expiredOnly=true 只取「已生效但已过期」，
     * excludeExpired=true 取「已生效且未过期」，避免「已生效」筛选把过期条目也算进去。</p>
     */
    @Query("SELECT k FROM KnowledgeEntry k WHERE " +
            "(:scopeAll = true OR k.companyId = :companyId OR k.companyId IS NULL) AND " +
            "(:scope IS NULL OR (:scope = 'public' AND k.companyId IS NULL) OR (:scope = 'private' AND k.companyId IS NOT NULL)) AND " +
            "(:publicActiveOnly = false OR k.companyId IS NOT NULL OR k.status = 'active') AND " +
            "(:category IS NULL OR k.category = :category) AND " +
            "(:sourceType IS NULL OR k.sourceType = :sourceType) AND " +
            "(:status IS NULL OR k.status = :status) AND " +
            "(:aiOnly = false OR k.generationJobId IS NOT NULL) AND " +
            "(:expiredOnly = false OR (k.status = 'active' AND k.expireAt IS NOT NULL AND k.expireAt < CURRENT_TIMESTAMP)) AND " +
            "(:excludeExpired = false OR k.status <> 'active' OR k.expireAt IS NULL OR k.expireAt >= CURRENT_TIMESTAMP) AND " +
            "(:keyword IS NULL OR k.title LIKE CONCAT('%', CAST(:keyword AS string), '%') " +
            " OR k.content LIKE CONCAT('%', CAST(:keyword AS string), '%')) " +
            "ORDER BY k.createdAt DESC, k.id DESC")
    Page<KnowledgeEntry> search(
            @Param("scopeAll") boolean scopeAll,
            @Param("companyId") UUID companyId,
            @Param("scope") String scope,
            @Param("publicActiveOnly") boolean publicActiveOnly,
            @Param("category") String category,
            @Param("sourceType") String sourceType,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("expiredOnly") boolean expiredOnly,
            @Param("excludeExpired") boolean excludeExpired,
            @Param("aiOnly") boolean aiOnly,
            Pageable pageable);

    /** 同版本链是否已有其他生效版本（恢复被取代版本时判断是否能直接回 active） */
    boolean existsByRootIdAndStatusAndIdNot(UUID rootId, String status, UUID id);

    /** 向量化成功：清空失败计数与下次重试时间 */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE knowledge_entry SET embedding_status='indexed', indexed_at=now(), "
            + "index_error=NULL, embedding_retry_count=0, embedding_next_retry_at=NULL WHERE id=:id",
            nativeQuery = true)
    int markIndexed(@Param("id") UUID id);

    /** 向量化失败：指数退避（5min * min(次数+1, 12)） */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE knowledge_entry SET embedding_status='failed', index_error=:error, "
            + "embedding_retry_count=embedding_retry_count+1, "
            + "embedding_next_retry_at=now() + (interval '5 minutes' * LEAST(embedding_retry_count + 1, 12)) "
            + "WHERE id=:id", nativeQuery = true)
    int markIndexFailed(@Param("id") UUID id, @Param("error") String error);

    /** 其它索引态（如 indexing） */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE knowledge_entry SET embedding_status=:status, index_error=:error WHERE id=:id",
            nativeQuery = true)
    int markIndexStatus(@Param("id") UUID id, @Param("status") String status,
                        @Param("error") String error);

    /** AI 草稿审核队列：某企业某状态下由生成任务产出的条目 */
    org.springframework.data.domain.Page<KnowledgeEntry> findByCompanyIdAndStatusAndGenerationJobIdIsNotNullOrderByCreatedAtDesc(
            UUID companyId, String status, Pageable pageable);

    /** 待审 AI 知识草稿数：用于阻止在未处理完上一批草稿时继续生成，避免堆积重复内容 */
    long countByCompanyIdAndStatusAndGenerationJobIdIsNotNull(UUID companyId, String status);

    /**
     * 更新向量嵌入 — pgvector 类型 JDBC 无法正确处理，用原生 SQL。
     * 照 {@code ExperienceGrainRepository.updateEmbedding} 的写法。
     */
    @Modifying
    @Query(value = "UPDATE knowledge_entry SET embedding = CAST(:embedding AS VECTOR) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embedding);

    /**
     * 同企业（或同为公共）下标题是否已存在，上传查重用。
     * 已废弃的不算重复：允许重新整理后再次入库；否则废弃过的标题永远传不进来。
     */
    @Query("SELECT COUNT(k) > 0 FROM KnowledgeEntry k WHERE " +
            "((:companyId IS NULL AND k.companyId IS NULL) OR k.companyId = :companyId) AND " +
            "k.status <> 'deprecated' AND LOWER(k.title) = :lowerTitle")
    boolean existsByCompanyScopeAndLowerTitle(@Param("companyId") UUID companyId,
                                              @Param("lowerTitle") String lowerTitle);

    /** 同企业同标题（不区分大小写）的未废弃条目，体检查重用 */
    @Query("SELECT k FROM KnowledgeEntry k WHERE "
            + "((:companyId IS NULL AND k.companyId IS NULL) OR k.companyId = :companyId) "
            + "AND k.status <> 'deprecated' AND LOWER(k.title) = :lowerTitle")
    List<KnowledgeEntry> findActiveByCompanyAndLowerTitle(@Param("companyId") UUID companyId,
                                                          @Param("lowerTitle") String lowerTitle);

    /** 同分类的其它已生效条目（体检相似/冲突提示用） */
    @Query("SELECT k FROM KnowledgeEntry k WHERE "
            + "((:companyId IS NULL AND k.companyId IS NULL) OR k.companyId = :companyId) "
            + "AND k.status = 'active' AND k.category = :category AND k.id <> :selfId "
            + "ORDER BY k.updatedAt DESC")
    List<KnowledgeEntry> findSimilarByCategory(@Param("companyId") UUID companyId,
                                               @Param("category") String category,
                                               @Param("selfId") UUID selfId,
                                               Pageable pageable);

    /** 某条知识派生出的新版本（AI 整理 / 人工修订），按版本倒序。 */
    List<KnowledgeEntry> findBySupersedesIdOrderByVersionDesc(UUID supersedesId);

    /** 是否还有派生版本引用这条知识（删除前校验，避免版本链断裂） */
    boolean existsBySupersedesId(UUID supersedesId);

    /** 批量取某组版本派生出的子版本（版本链向下补齐）。 */
    List<KnowledgeEntry> findBySupersedesIdIn(List<UUID> supersedesIds);
}
