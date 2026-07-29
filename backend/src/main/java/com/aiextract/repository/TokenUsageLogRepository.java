package com.aiextract.repository;

import com.aiextract.model.TokenUsageLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TokenUsageLogRepository extends JpaRepository<TokenUsageLog, UUID> {

    /** 按天聚合 token 用量（最近 N 天） */
    @Query("SELECT t.usageDate, SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t) " +
           "FROM TokenUsageLog t " +
           "WHERE t.usageDate >= :since " +
           "GROUP BY t.usageDate ORDER BY t.usageDate DESC")
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

    // ========== 企业数据范围过滤方法 ==========

    /** 按天聚合 token 用量（最近 N 天，限定用户） */
    @Query("SELECT t.usageDate, SUM(t.inputTokens), SUM(t.outputTokens), COUNT(t) " +
           "FROM TokenUsageLog t " +
           "WHERE t.usageDate >= :since AND t.userId IN :userIds " +
           "GROUP BY t.usageDate ORDER BY t.usageDate DESC")
    List<Object[]> sumByDateSinceAndUserIdIn(@Param("since") LocalDate since, @Param("userIds") List<UUID> userIds);

    /** 指定日期的汇总（限定用户） */
    @Query("SELECT COALESCE(SUM(t.inputTokens), 0), COALESCE(SUM(t.outputTokens), 0), COUNT(t) " +
           "FROM TokenUsageLog t WHERE t.usageDate = :date AND t.userId IN :userIds")
    List<Object[]> sumByDateAndUserIdIn(@Param("date") LocalDate date, @Param("userIds") List<UUID> userIds);

    /** 全量汇总（限定用户） */
    @Query("SELECT COALESCE(SUM(t.inputTokens), 0), COALESCE(SUM(t.outputTokens), 0), COUNT(t) " +
           "FROM TokenUsageLog t WHERE t.userId IN :userIds")
    List<Object[]> sumTotalByUserIdIn(@Param("userIds") List<UUID> userIds);

    /** 分页明细（限定用户，按创建时间倒序） */
    @Query("SELECT t FROM TokenUsageLog t WHERE t.userId IN :userIds ORDER BY t.createdAt DESC")
    List<TokenUsageLog> findRecentByUserIdIn(@Param("userIds") List<UUID> userIds, Pageable pageable);

    /** 总记录数（限定用户） */
    @Query("SELECT COUNT(t) FROM TokenUsageLog t WHERE t.userId IN :userIds")
    long countByUserIdIn(@Param("userIds") List<UUID> userIds);
}
