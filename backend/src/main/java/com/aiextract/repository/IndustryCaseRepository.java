package com.aiextract.repository;

import com.aiextract.model.IndustryCase;
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
 * 行业案例数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-27
 */
@Repository
public interface IndustryCaseRepository extends JpaRepository<IndustryCase, UUID> {

    /** 同一版本链下指定状态的条目，用于保证同 root 只有一个 active */
    List<IndustryCase> findByRootIdAndStatus(UUID rootId, String status);

    /** 同企业同标题的待审副本：公共案例复制去重（连点不产生多个 pending） */
    java.util.Optional<IndustryCase> findFirstByCompanyIdAndTitleAndStatusOrderByCreatedAtDesc(
            UUID companyId, String title, String status);

    /** 员工「我的提交」 */
    Page<IndustryCase> findByCompanyIdAndContributorIdOrderByCreatedAtDesc(
            UUID companyId, UUID contributorId, Pageable pageable);

    /** 批量取标题等轻量场景：SQL 层过滤企业，跨企业条目不会查出来 */
    @Query("SELECT c FROM IndustryCase c WHERE c.id IN :ids "
            + "AND (c.companyId = :companyId OR c.companyId IS NULL)")
    List<IndustryCase> findVisibleByIdIn(@Param("ids") java.util.Collection<UUID> ids,
                                         @Param("companyId") UUID companyId);

    /** 向量化失败/待索引且到达重试时间的条目 */
    @Query("SELECT c.id FROM IndustryCase c WHERE c.status = 'active' "
            + "AND c.embeddingStatus IN ('pending','failed') "
            + "AND (c.embeddingNextRetryAt IS NULL OR c.embeddingNextRetryAt <= :now) "
            + "ORDER BY c.updatedAt ASC")
    List<UUID> findEmbeddingRetryIds(@Param("now") java.time.LocalDateTime now, Pageable pageable);

    /**
     * 分页检索案例 — 单条 JPQL，无 N+1。
     *
     * <p>scopeAll=true 时不过滤企业（super_admin 看全部），否则过滤「本企业 + 平台公共」。
     * keyword 匹配标题、异议、话术、可复用点与场景标签，避免「客户原话在正文里搜不到」。</p>
     *
     * <p>过期态由 expire_at 派生：expiredOnly=true 只取「已生效但已过期」，
     * excludeExpired=true 取「已生效且未过期」。</p>
     */
    @Query("SELECT c FROM IndustryCase c WHERE " +
            "(:scopeAll = true OR c.companyId = :companyId OR c.companyId IS NULL) AND " +
            "(:scope IS NULL OR (:scope = 'public' AND c.companyId IS NULL) " +
            " OR (:scope = 'private' AND c.companyId IS NOT NULL)) AND " +
            "(:publicActiveOnly = false OR c.companyId IS NOT NULL OR c.status = 'active') AND " +
            "(:funnelStage IS NULL OR c.funnelStage = :funnelStage) AND " +
            "(:sceneTag IS NULL OR c.sceneTag = :sceneTag) AND " +
            "(:result IS NULL OR c.result = :result) AND " +
            "(:status IS NULL OR c.status = :status) AND " +
            "(:aiOnly = false OR c.generationJobId IS NOT NULL) AND " +
            "(:expiredOnly = false OR (c.status = 'active' AND c.expireAt IS NOT NULL AND c.expireAt < CURRENT_TIMESTAMP)) AND " +
            "(:excludeExpired = false OR c.status <> 'active' OR c.expireAt IS NULL OR c.expireAt >= CURRENT_TIMESTAMP) AND " +
            "(:keyword IS NULL OR c.title LIKE CONCAT('%', CAST(:keyword AS string), '%') " +
            " OR c.objection LIKE CONCAT('%', CAST(:keyword AS string), '%') " +
            " OR c.scripts LIKE CONCAT('%', CAST(:keyword AS string), '%') " +
            " OR c.reusablePoints LIKE CONCAT('%', CAST(:keyword AS string), '%') " +
            " OR c.sceneTag LIKE CONCAT('%', CAST(:keyword AS string), '%')) " +
            "ORDER BY c.createdAt DESC, c.id DESC")
    Page<IndustryCase> search(
            @Param("scopeAll") boolean scopeAll,
            @Param("companyId") UUID companyId,
            @Param("scope") String scope,
            @Param("publicActiveOnly") boolean publicActiveOnly,
            @Param("funnelStage") String funnelStage,
            @Param("sceneTag") String sceneTag,
            @Param("result") String result,
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("expiredOnly") boolean expiredOnly,
            @Param("excludeExpired") boolean excludeExpired,
            @Param("aiOnly") boolean aiOnly,
            Pageable pageable);

    /** 同版本链是否已有其他生效版本（恢复被取代版本时判断是否能直接回 active） */
    boolean existsByRootIdAndStatusAndIdNot(UUID rootId, String status, UUID id);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE industry_case SET embedding_status='indexed', indexed_at=now(), "
            + "index_error=NULL, embedding_retry_count=0, embedding_next_retry_at=NULL WHERE id=:id",
            nativeQuery = true)
    int markIndexed(@Param("id") UUID id);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE industry_case SET embedding_status='failed', index_error=:error, "
            + "embedding_retry_count=embedding_retry_count+1, "
            + "embedding_next_retry_at=now() + (interval '5 minutes' * LEAST(embedding_retry_count + 1, 12)) "
            + "WHERE id=:id", nativeQuery = true)
    int markIndexFailed(@Param("id") UUID id, @Param("error") String error);

    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE industry_case SET embedding_status=:status, index_error=:error WHERE id=:id",
            nativeQuery = true)
    int markIndexStatus(@Param("id") UUID id, @Param("status") String status,
                        @Param("error") String error);

    /** AI 草稿审核队列：某企业某状态下由生成任务产出的案例 */
    org.springframework.data.domain.Page<IndustryCase> findByCompanyIdAndStatusAndGenerationJobIdIsNotNullOrderByCreatedAtDesc(
            UUID companyId, String status, Pageable pageable);

    /** 待审 AI 案例草稿数：用于阻止在未处理完上一批草稿时继续生成 */
    long countByCompanyIdAndStatusAndGenerationJobIdIsNotNull(UUID companyId, String status);

    /** 当前可见范围内出现过的场景标签（案例列表筛选下拉用去重值，不写死枚举）。 */
    @Query("SELECT DISTINCT c.sceneTag FROM IndustryCase c WHERE " +
            "c.sceneTag IS NOT NULL AND c.sceneTag <> '' AND " +
            "(:scopeAll = true OR c.companyId = :companyId OR c.companyId IS NULL) " +
            "ORDER BY c.sceneTag")
    List<String> findDistinctSceneTags(@Param("scopeAll") boolean scopeAll,
                                       @Param("companyId") UUID companyId);

    /**
     * 更新向量嵌入 — pgvector 类型 JDBC 无法正确处理，用原生 SQL。
     */
    @Modifying
    @Query(value = "UPDATE industry_case SET embedding = CAST(:embedding AS VECTOR) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embedding);

    /** 同企业（或同为公共）下标题是否已存在，上传查重用；已废弃的不算重复 */
    @Query("SELECT COUNT(c) > 0 FROM IndustryCase c WHERE " +
            "((:companyId IS NULL AND c.companyId IS NULL) OR c.companyId = :companyId) AND " +
            "c.status <> 'deprecated' AND LOWER(c.title) = :lowerTitle")
    boolean existsByCompanyScopeAndLowerTitle(@Param("companyId") UUID companyId,
                                              @Param("lowerTitle") String lowerTitle);

    /** 同企业同标题（不区分大小写）的未废弃案例，体检查重用 */
    @Query("SELECT c FROM IndustryCase c WHERE "
            + "((:companyId IS NULL AND c.companyId IS NULL) OR c.companyId = :companyId) "
            + "AND c.status <> 'deprecated' AND LOWER(c.title) = :lowerTitle")
    List<IndustryCase> findActiveByCompanyAndLowerTitle(@Param("companyId") UUID companyId,
                                                        @Param("lowerTitle") String lowerTitle);

    /** 同场景的其它已生效案例（成功/失败对照提示） */
    @Query("SELECT c FROM IndustryCase c WHERE "
            + "((:companyId IS NULL AND c.companyId IS NULL) OR c.companyId = :companyId) "
            + "AND c.status = 'active' AND c.sceneTag = :sceneTag AND c.id <> :selfId "
            + "ORDER BY c.updatedAt DESC")
    List<IndustryCase> findSimilarByScene(@Param("companyId") UUID companyId,
                                          @Param("sceneTag") String sceneTag,
                                          @Param("selfId") UUID selfId,
                                          Pageable pageable);

    /**
     * 对练开练用：按场景标签取一条可检索的 active 案例。
     *
     * <p>开练接口是普通 HTTP JSON 请求，不能在这里调用向量模型；
     * 用 SQL 精确匹配场景标签代替 ANN，只用于补客户背景。</p>
     */
    @Query("SELECT c FROM IndustryCase c WHERE "
            + "((:companyId IS NULL AND c.companyId IS NULL) OR c.companyId = :companyId) "
            + "AND c.status = 'active' AND c.sceneTag = :sceneTag "
            + "AND (c.effectiveAt IS NULL OR c.effectiveAt <= CURRENT_TIMESTAMP) "
            + "AND (c.expireAt IS NULL OR c.expireAt > CURRENT_TIMESTAMP) "
            + "ORDER BY c.updatedAt DESC")
    List<IndustryCase> findActiveForPractice(@Param("companyId") UUID companyId,
                                             @Param("sceneTag") String sceneTag,
                                             Pageable pageable);

    /** 某条案例派生出的新版本（AI 整理 / 人工修订），按版本倒序。 */
    List<IndustryCase> findBySupersedesIdOrderByVersionDesc(UUID supersedesId);

    /** 是否还有派生版本引用这条案例（删除前校验，避免版本链断裂） */
    boolean existsBySupersedesId(UUID supersedesId);

    /** 批量取某组版本派生出的子版本（版本链向下补齐）。 */
    List<IndustryCase> findBySupersedesIdIn(List<UUID> supersedesIds);
}
