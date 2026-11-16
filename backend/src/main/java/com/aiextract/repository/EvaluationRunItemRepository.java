package com.aiextract.repository;

import com.aiextract.model.EvaluationRunItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 评测单题执行数据访问接口。
 *
 * <p>租约领取（原子 UPDATE）由 Worker 在 T2 落地，同样放在本接口，不在 Scheduler 里拼 SQL。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface EvaluationRunItemRepository extends JpaRepository<EvaluationRunItem, UUID> {

    /**
     * 单题执行分页（详情页列表）：按题目题序、尝试号升序，与题集编辑器顺序一致。
     *
     * <p>run_item 自身不存 order_no（题序属于题目），因此与 evaluation_item 内连接取 orderNo。
     * 连接走 item 主键，run_item 侧走 {@code idx_run_item_run}。</p>
     */
    @Query(value = "SELECT ri FROM EvaluationRunItem ri, EvaluationItem i "
            + "WHERE ri.itemId = i.id AND ri.runId = :runId "
            // 只展示每题最新一次尝试：重试会新增行，旧行仍保留用于审计，但不能再出现在详情列表
            + "AND ri.attemptNo = (SELECT MAX(r2.attemptNo) FROM EvaluationRunItem r2 "
            + "  WHERE r2.runId = :runId AND r2.itemId = ri.itemId) "
            + "ORDER BY i.orderNo ASC",
            countQuery = "SELECT count(ri) FROM EvaluationRunItem ri WHERE ri.runId = :runId "
                    + "AND ri.attemptNo = (SELECT MAX(r2.attemptNo) FROM EvaluationRunItem r2 "
                    + "  WHERE r2.runId = :runId AND r2.itemId = ri.itemId)")
    Page<EvaluationRunItem> findDetailPageByRunId(@Param("runId") UUID runId, Pageable pageable);

    /** 单题最新一次尝试（详情抽屉按 itemId 取） */
    Optional<EvaluationRunItem> findFirstByRunIdAndItemIdOrderByAttemptNoDesc(UUID runId, UUID itemId);

    /** 批次内某执行态的题目（如 pending 待领取、error 待重试） */
    List<EvaluationRunItem> findByRunIdAndStatus(UUID runId, String status);

    /** 批次内多个执行态的题目（如 running/cancel 清理在途） */
    List<EvaluationRunItem> findByRunIdAndStatusIn(UUID runId, Collection<String> statuses);

    /** 计数：批次内某执行态题数（进度聚合） */
    long countByRunIdAndStatus(UUID runId, String status);

    /**
     * 批次内「每题最新一次尝试」的完整记录（详情、重试、定稿都用这一次查询）。
     *
     * <p>重试会新增行，旧行证据保留；面向用户与统计的只有最新行。</p>
     */
    @Query(value = "SELECT * FROM evaluation_run_item ri WHERE ri.id IN ("
            + "  SELECT DISTINCT ON (item_id) id FROM evaluation_run_item "
            + "  WHERE run_id = :runId ORDER BY item_id, attempt_no DESC"
            + ")", nativeQuery = true)
    List<EvaluationRunItem> findLatestAttempts(@Param("runId") UUID runId);

    /**
     * 取消批次时把未执行的题标记为 cancelled。
     * 不标记的话，取消后再点「重试失败题」会把整批 pending 题复活。
     */
    @Modifying
    @Query(value = "UPDATE evaluation_run_item SET status = 'cancelled', lease_owner = NULL, "
            + "lease_expires_at = NULL, updated_at = now() "
            + "WHERE run_id = :runId AND status IN ('pending', 'generating')", nativeQuery = true)
    int markCancelledByRun(@Param("runId") UUID runId);

    /** 引用保护：题目是否已被任何运行引用（删题前校验，返回 409） */
    boolean existsByItemId(UUID itemId);

    // ==================== 租约（P1 Worker） ====================

    /**
     * 锁定可领取的题目行（行锁 + SKIP LOCKED，多实例安全）。
     *
     * <p>条件必须同时包含 {@code pending} 与 {@code generating}：进程崩在 generating 的行
     * 只能靠「租约过期被重新领取」恢复（决策 D5）。只捞 pending 会让这些行永久卡死。</p>
     */
    @Query(value = "SELECT id FROM evaluation_run_item "
            + "WHERE run_id = :runId AND status IN ('pending', 'generating') "
            + "AND (lease_expires_at IS NULL OR lease_expires_at < now()) "
            + "ORDER BY created_at, id LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<UUID> lockClaimableIds(@Param("runId") UUID runId, @Param("limit") int limit);

    /**
     * 原子领取：置 generating + 写租约。仅当行仍处于可领取状态时生效（防锁释放后被他人抢走）。
     *
     * @return 实际领取行数，理论等于入参 ids 数量
     */
    @Modifying
    @Query(value = "UPDATE evaluation_run_item "
            + "SET status = 'generating', lease_owner = :owner, lease_expires_at = :leaseUntil, "
            + "    heartbeat_at = now(), updated_at = now() "
            + "WHERE id IN (:ids) AND status IN ('pending', 'generating') "
            + "AND (lease_expires_at IS NULL OR lease_expires_at < now())",
            nativeQuery = true)
    int claimItems(@Param("ids") List<UUID> ids, @Param("owner") String owner,
                   @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 心跳续租：只续自己持有的在途行；进程死亡后自然停止续租，租约到期即可被重新领取。 */
    @Modifying
    @Query(value = "UPDATE evaluation_run_item "
            + "SET lease_expires_at = :leaseUntil, heartbeat_at = now(), updated_at = now() "
            + "WHERE lease_owner = :owner AND status = 'generating'",
            nativeQuery = true)
    int renewLeases(@Param("owner") String owner, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 释放本 worker 持有的全部租约（取消运行/关机时用），不改变执行态。 */
    @Modifying
    @Query(value = "UPDATE evaluation_run_item SET lease_owner = NULL, lease_expires_at = NULL, updated_at = now() "
            + "WHERE run_id = :runId AND lease_owner = :owner",
            nativeQuery = true)
    int releaseLeases(@Param("runId") UUID runId, @Param("owner") String owner);

    /**
     * 批次内「每题最新一次尝试」的执行态计数（重试不重复计数）。
     *
     * @return 每行 [status, count]
     */
    @Query(value = "SELECT t.status, count(*) FROM ("
            + "  SELECT DISTINCT ON (item_id) item_id, status FROM evaluation_run_item "
            + "  WHERE run_id = :runId ORDER BY item_id, attempt_no DESC"
            + ") t GROUP BY t.status", nativeQuery = true)
    List<Object[]> countLatestStatusByRun(@Param("runId") UUID runId);

    /**
     * 批次内「每题最新一次尝试」的业务结论计数。
     *
     * @return 每行 [verdict, count]
     */
    @Query(value = "SELECT t.verdict, count(*) FROM ("
            + "  SELECT DISTINCT ON (item_id) item_id, verdict FROM evaluation_run_item "
            + "  WHERE run_id = :runId ORDER BY item_id, attempt_no DESC"
            + ") t WHERE t.verdict IS NOT NULL GROUP BY t.verdict", nativeQuery = true)
    List<Object[]> countLatestVerdictByRun(@Param("runId") UUID runId);

    /**
     * 进入评分态并先落回答与证据：答题完成但评分失败时，「回答成功、无法判分」仍可追溯。
     */
    @Modifying
    @Query(value = "UPDATE evaluation_run_item SET status = 'scoring', ai_answer = :answer, "
            + "retrieval_evidence = CAST(:evidence AS jsonb), updated_at = now() "
            + "WHERE id = :id AND lease_owner = :owner", nativeQuery = true)
    int markScoring(@Param("id") UUID id, @Param("owner") String owner,
                    @Param("answer") String answer, @Param("evidence") String evidence);

    /** 写回成功结果并释放租约（仅当本 worker 仍持有该行，防止覆盖他人领取后的结果）。 */
    @Modifying
    @Query(value = "UPDATE evaluation_run_item SET status = 'completed', verdict = :verdict, "
            + "ai_answer = :answer, retrieval_evidence = CAST(:evidence AS jsonb), "
            + "rule_check_result = CAST(:ruleCheck AS jsonb), ai_score = CAST(:score AS jsonb), "
            + "error_message = NULL, lease_owner = NULL, lease_expires_at = NULL, updated_at = now() "
            + "WHERE id = :id AND lease_owner = :owner", nativeQuery = true)
    int completeItem(@Param("id") UUID id, @Param("owner") String owner, @Param("verdict") String verdict,
                     @Param("answer") String answer, @Param("evidence") String evidence,
                     @Param("ruleCheck") String ruleCheck, @Param("score") String score);

    /** 人工复核后改写业务结论（执行态不变，AI 原分保留在 ai_score）。 */
    @Modifying
    @Query("UPDATE EvaluationRunItem ri SET ri.verdict = :verdict, ri.updatedAt = :now WHERE ri.id = :id")
    int updateVerdict(@Param("id") UUID id, @Param("verdict") String verdict, @Param("now") LocalDateTime now);

    /** 记为执行错误并释放租约；执行态与业务结论分离，error 不写 verdict。 */
    @Modifying
    @Query(value = "UPDATE evaluation_run_item SET status = 'error', error_message = :message, "
            + "lease_owner = NULL, lease_expires_at = NULL, updated_at = now() "
            + "WHERE id = :id AND lease_owner = :owner", nativeQuery = true)
    int markError(@Param("id") UUID id, @Param("owner") String owner, @Param("message") String message);

    /**
     * 批次平均分：只统计每题最新尝试，且**优先采用人工复核的改分**。
     *
     * <p>人工改分保存在 evaluation_review.adjusted_score，若均分只看 ai_score，
     * 复核结果就不会进入报告 —— 复核等于白做。这里取每题最新一条「有改分」的复核记录，
     * 没有改分才回落到 AI 原分。</p>
     */
    @Query(value = "SELECT avg(COALESCE(r.adjusted_total, (t.ai_score->>'total_score')::numeric)) FROM ("
            + "  SELECT DISTINCT ON (item_id) id, item_id, ai_score FROM evaluation_run_item "
            + "  WHERE run_id = :runId ORDER BY item_id, attempt_no DESC"
            + ") t LEFT JOIN LATERAL ("
            + "  SELECT (ev.adjusted_score->>'total_score')::numeric AS adjusted_total "
            + "  FROM evaluation_review ev WHERE ev.run_item_id = t.id AND ev.adjusted_score IS NOT NULL "
            + "  ORDER BY ev.created_at DESC LIMIT 1"
            + ") r ON true WHERE t.ai_score IS NOT NULL", nativeQuery = true)
    Double avgTotalScoreByRun(@Param("runId") UUID runId);

    /**
     * 企业内评测失败题（最新一次尝试）—— 内容缺口信号与「错题转草稿」来源。
     */
    @Query(value = """
            SELECT i.id, i.question, i.scene_tag, i.expected_points, ri.ai_answer, r.id AS run_id
              FROM evaluation_run_item ri
              JOIN evaluation_run r ON r.id = ri.run_id
              JOIN evaluation_item i ON i.id = ri.item_id
             WHERE r.company_id = :companyId
               AND ri.status = 'completed'
               AND ri.verdict = 'fail'
               AND ri.attempt_no = (SELECT MAX(x.attempt_no) FROM evaluation_run_item x
                                     WHERE x.run_id = ri.run_id AND x.item_id = ri.item_id)
             ORDER BY COALESCE(r.finished_at, r.created_at) DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findRecentFailedItems(@Param("companyId") UUID companyId,
                                         @Param("limit") int limit);

    /** 题目统计：运行次数、通过数、平均分（数据集维度，最新尝试） */
    @Query(value = """
            SELECT ri.item_id,
                   COUNT(*) AS attempts,
                   COUNT(*) FILTER (WHERE ri.verdict = 'pass') AS passed,
                   AVG((ri.ai_score ->> 'total_score')::numeric) AS avg_score
              FROM evaluation_run_item ri
              JOIN evaluation_run r ON r.id = ri.run_id
             WHERE r.dataset_id = :datasetId AND r.company_id = :companyId
               AND ri.status = 'completed'
               AND ri.attempt_no = (SELECT MAX(x.attempt_no) FROM evaluation_run_item x
                                     WHERE x.run_id = ri.run_id AND x.item_id = ri.item_id)
             GROUP BY ri.item_id
            """, nativeQuery = true)
    List<Object[]> aggregateItemStats(@Param("datasetId") UUID datasetId,
                                      @Param("companyId") UUID companyId);

    /** 每道题在每个批次里的通过情况（区分度计算用） */
    @Query(value = """
            SELECT ri.item_id, ri.run_id, ri.verdict,
                   (ri.ai_score ->> 'total_score')::numeric AS score
              FROM evaluation_run_item ri
              JOIN evaluation_run r ON r.id = ri.run_id
             WHERE r.dataset_id = :datasetId AND r.company_id = :companyId
               AND ri.status = 'completed'
               AND ri.attempt_no = (SELECT MAX(x.attempt_no) FROM evaluation_run_item x
                                     WHERE x.run_id = ri.run_id AND x.item_id = ri.item_id)
            """, nativeQuery = true)
    List<Object[]> findItemRunOutcomes(@Param("datasetId") UUID datasetId,
                                       @Param("companyId") UUID companyId);

    /** 人工复核次数与推翻次数（金标争议率） */
    @Query(value = """
            SELECT ri.item_id, COUNT(rev.id) AS reviews,
                   COUNT(rev.id) FILTER (WHERE rev.action IN ('adjust', 'undetermined')) AS overturns
              FROM evaluation_review rev
              JOIN evaluation_run_item ri ON ri.id = rev.run_item_id
              JOIN evaluation_run r ON r.id = ri.run_id
             WHERE r.dataset_id = :datasetId AND r.company_id = :companyId
             GROUP BY ri.item_id
            """, nativeQuery = true)
    List<Object[]> aggregateReviewStats(@Param("datasetId") UUID datasetId,
                                        @Param("companyId") UUID companyId);
}
