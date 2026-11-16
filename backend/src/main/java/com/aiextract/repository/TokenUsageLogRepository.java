package com.aiextract.repository;

import com.aiextract.model.TokenUsageLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TokenUsageLogRepository extends JpaRepository<TokenUsageLog, UUID> {

    /** 按天聚合 token 用量（最近 N 天，日期升序，便于前端直接画时间轴） */
    @Query("SELECT t.usageDate, SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t) " +
           "FROM TokenUsageLog t " +
           "WHERE t.usageDate >= :since " +
           "GROUP BY t.usageDate ORDER BY t.usageDate ASC")
    List<Object[]> sumByDateSince(@Param("since") LocalDate since);

    /** 指定日期的汇总（无记录时返回 [0,0,0]） */
    @Query("SELECT COALESCE(SUM(t.inputTokens), 0), COALESCE(SUM(t.outputTokens), 0), COUNT(t) " +
           "FROM TokenUsageLog t WHERE t.usageDate = :date")
    List<Object[]> sumByDate(@Param("date") LocalDate date);

    /** 全量汇总（无 GROUP BY，一次聚合，不扫全表逐行） */
    @Query("SELECT COALESCE(SUM(t.inputTokens), 0), COALESCE(SUM(t.outputTokens), 0), COUNT(t) " +
           "FROM TokenUsageLog t")
    List<Object[]> sumTotal();

    /** 分页明细（按创建时间倒序） */
    @Query("SELECT t FROM TokenUsageLog t ORDER BY t.createdAt DESC")
    List<TokenUsageLog> findRecent(Pageable pageable);

    /** 总记录数 */
    @Query("SELECT COUNT(t) FROM TokenUsageLog t")
    long countAll();

    // ========== 企业数据范围过滤方法（按 company_id，覆盖 userId 为空的内部调用） ==========

    /** 按天聚合（限定企业，日期升序） */
    @Query("SELECT t.usageDate, SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t) " +
           "FROM TokenUsageLog t " +
           "WHERE t.usageDate >= :since AND t.companyId = :companyId " +
           "GROUP BY t.usageDate ORDER BY t.usageDate ASC")
    List<Object[]> sumByDateSinceAndCompanyId(@Param("since") LocalDate since,
                                              @Param("companyId") UUID companyId);

    /** 指定日期的汇总（限定企业） */
    @Query("SELECT COALESCE(SUM(t.inputTokens), 0), COALESCE(SUM(t.outputTokens), 0), COUNT(t) " +
           "FROM TokenUsageLog t WHERE t.usageDate = :date AND t.companyId = :companyId")
    List<Object[]> sumByDateAndCompanyId(@Param("date") LocalDate date, @Param("companyId") UUID companyId);

    /** 全量汇总（限定企业） */
    @Query("SELECT COALESCE(SUM(t.inputTokens), 0), COALESCE(SUM(t.outputTokens), 0), COUNT(t) " +
           "FROM TokenUsageLog t WHERE t.companyId = :companyId")
    List<Object[]> sumTotalByCompanyId(@Param("companyId") UUID companyId);

    /** 分页明细（限定企业，按创建时间倒序） */
    @Query("SELECT t FROM TokenUsageLog t WHERE t.companyId = :companyId ORDER BY t.createdAt DESC")
    List<TokenUsageLog> findRecentByCompanyId(@Param("companyId") UUID companyId, Pageable pageable);

    /** 总记录数（限定企业） */
    @Query("SELECT COUNT(t) FROM TokenUsageLog t WHERE t.companyId = :companyId")
    long countByCompanyId(@Param("companyId") UUID companyId);

    /** 保留策略 dry-run：到期记录数 */
    long countByUsageDateBefore(LocalDate cutoff);

    /** 按模型聚合（超管全量） */
    @Query("SELECT COALESCE(t.modelName, t.modelType), SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t) " +
           "FROM TokenUsageLog t WHERE t.usageDate >= :since " +
           "GROUP BY COALESCE(t.modelName, t.modelType) " +
           "ORDER BY SUM(t.inputTokens) + SUM(t.outputTokens) DESC")
    List<Object[]> sumByModelSince(@Param("since") LocalDate since);

    /** 按模型聚合（限定企业） */
    @Query("SELECT COALESCE(t.modelName, t.modelType), SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t) " +
           "FROM TokenUsageLog t WHERE t.usageDate >= :since AND t.companyId = :companyId " +
           "GROUP BY COALESCE(t.modelName, t.modelType) " +
           "ORDER BY SUM(t.inputTokens) + SUM(t.outputTokens) DESC")
    List<Object[]> sumByModelSinceAndCompanyId(@Param("since") LocalDate since,
                                               @Param("companyId") UUID companyId);

    /** 企业用量排行（超管视角，最近 N 天） */
    @Query("SELECT t.companyId, SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t) " +
           "FROM TokenUsageLog t WHERE t.usageDate >= :since AND t.companyId IS NOT NULL " +
           "GROUP BY t.companyId ORDER BY SUM(t.inputTokens) + SUM(t.outputTokens) DESC")
    List<Object[]> topCompaniesSince(@Param("since") LocalDate since, Pageable pageable);

    /**
     * DATA-02 归档：单条 SQL 原子搬移一批到期记录到归档表（不丢历史）。
     * 每批最多 limit 行，返回搬移条数；归档表 id 冲突时跳过。
     */
    @Modifying
    @Query(value = "WITH moved AS (" +
            " DELETE FROM public.token_usage_log WHERE id IN (" +
            "   SELECT id FROM public.token_usage_log WHERE usage_date < :cutoff LIMIT :limit" +
            " ) RETURNING *" +
            ") INSERT INTO public.token_usage_log_archive" +
            " SELECT moved.*, now() FROM moved ON CONFLICT (id) DO NOTHING", nativeQuery = true)
    int archiveBatch(@Param("cutoff") LocalDate cutoff, @Param("limit") int limit);

    // ========== 企业维度 ==========

    interface CompanyUsageTotal {
        Long getInputTokens();

        Long getOutputTokens();
    }

    /** 企业当月用量（平台 Key 与企业 Key 都归属企业） */
    @Query(value = "SELECT COALESCE(SUM(input_tokens), 0) AS \"inputTokens\", "
            + "COALESCE(SUM(output_tokens), 0) AS \"outputTokens\" "
            + "FROM token_usage_log WHERE company_id = :companyId AND usage_date >= :monthStart",
            nativeQuery = true)
    CompanyUsageTotal sumByCompanySince(@Param("companyId") UUID companyId,
                                        @Param("monthStart") LocalDate monthStart);
}
