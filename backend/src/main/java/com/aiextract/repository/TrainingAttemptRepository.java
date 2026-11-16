package com.aiextract.repository;

import com.aiextract.model.TrainingAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 培训尝试数据访问接口。
 *
 * <p>评分领取用 {@code FOR UPDATE SKIP LOCKED}，且必须同时覆盖 {@code submitted} 与
 * {@code grading} 两种状态：worker 置 grading 后若进程崩溃，租约到期必须能重新领取，
 * 否则该行永久卡在「评分中」。对应部分索引 {@code idx_tat_claim}。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface TrainingAttemptRepository extends JpaRepository<TrainingAttempt, UUID> {

    /** 归属校验：尝试必须属于该分配 */
    Optional<TrainingAttempt> findByIdAndAssignmentId(UUID id, UUID assignmentId);

    /** 该进度行最近一次尝试（续接/补考判断） */
    Optional<TrainingAttempt> findFirstByTaskRecordIdOrderByAttemptNoDesc(UUID taskRecordId);

    /** 该进度行的全部尝试（成绩历史，不删旧证据） */
    List<TrainingAttempt> findByTaskRecordIdOrderByAttemptNoDesc(UUID taskRecordId);

    /** 导师工作台：批量取待复核尝试 */
    List<TrainingAttempt> findByAssignmentIdInAndStatusOrderBySubmittedAtAsc(
            java.util.Collection<UUID> assignmentIds, String status);

    /** 下次尝试号 */
    @Query("SELECT COALESCE(MAX(a.attemptNo), 0) FROM TrainingAttempt a WHERE a.taskRecordId = :taskRecordId")
    int findMaxAttemptNo(@Param("taskRecordId") UUID taskRecordId);

    /** 已出结论的尝试次数（补考上限判定；error/进行中不占用次数） */
    long countByTaskRecordIdAndStatusIn(UUID taskRecordId, java.util.Collection<String> statuses);

    /** 批量统计每条进度行真正占用次数的尝试（filled + pending_review），用于任务列表 */
    @org.springframework.data.jpa.repository.Query(
            "SELECT a.taskRecordId, COUNT(a) FROM TrainingAttempt a "
            + "WHERE a.taskRecordId IN :recordIds AND a.status IN :statuses "
            + "GROUP BY a.taskRecordId")
    java.util.List<Object[]> countUsedByRecordIds(
            @org.springframework.data.repository.query.Param("recordIds") java.util.Collection<UUID> recordIds,
            @org.springframework.data.repository.query.Param("statuses") java.util.Collection<String> statuses);

    /** 某分配是否还有未裁决尝试（认证前置检查） */
    boolean existsByAssignmentIdAndStatusIn(UUID assignmentId, java.util.Collection<String> statuses);

    /**
     * 批次看板：每个分配最早一条待复核尝试的提交时间。
     *
     * <p>只取聚合投影，不加载 attempt 实体——attempt 带 answers/score 大 JSONB，
     * 一页 200 人 × 多任务 × 多次尝试会把整页内容拉进内存。</p>
     */
    @Query("SELECT a.assignmentId, MIN(a.submittedAt) FROM TrainingAttempt a "
            + "WHERE a.assignmentId IN :assignmentIds AND a.status = 'pending_review' "
            + "AND a.submittedAt IS NOT NULL GROUP BY a.assignmentId")
    List<Object[]> findOldestPendingSubmitByAssignmentIds(
            @Param("assignmentIds") java.util.Collection<UUID> assignmentIds);

    /** 延期时要重算截止的进行中考试（陪练没有 attempt.deadline） */
    @Query(value = "SELECT * FROM training_attempt WHERE assignment_id = :assignmentId "
            + "AND type = 'exam' AND status = 'in_progress' AND submitted_at IS NULL", nativeQuery = true)
    List<TrainingAttempt> findInProgressExamAttempts(@Param("assignmentId") UUID assignmentId);

    /**
     * 滞留的待复核/评分失败尝试（按进入待定的时间排序）。
     *
     * <p>谓词拆成两个分支而不用 COALESCE(status 整体)：整列 COALESCE 会让
     * {@code idx_tat_pending_review_age} 部分索引失效，每 5 分钟退化为顺序扫描。</p>
     */
    @Query(value = "SELECT id FROM training_attempt "
            + "WHERE (status = 'pending_review' AND submitted_at < :before) "
            + "   OR (status = 'error' AND COALESCE(finished_at, created_at) < :before) "
            + "ORDER BY CASE WHEN status = 'pending_review' THEN submitted_at "
            + "              ELSE COALESCE(finished_at, created_at) END "
            + "LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<UUID> findStaleReviewIds(@Param("before") LocalDateTime before,
                                  @Param("limit") int limit,
                                  @Param("offset") int offset);

    /** 只延长不缩短：旧值作为条件，避免并发互相覆盖 */
    @Modifying
    @Query(value = "UPDATE training_attempt SET deadline = :newDeadline, updated_at = now() "
            + "WHERE id = :id AND status = 'in_progress' AND deadline = :oldDeadline", nativeQuery = true)
    int extendAttemptDeadline(@Param("id") UUID id,
                              @Param("oldDeadline") LocalDateTime oldDeadline,
                              @Param("newDeadline") LocalDateTime newDeadline);

    /** 该进度行是否还有进行中的尝试（续接用） */
    Optional<TrainingAttempt> findFirstByTaskRecordIdAndStatusOrderByAttemptNoDesc(UUID taskRecordId, String status);

    /** 最近的「已提交/评分中/待复核」尝试（重进任务时直接恢复，不新建） */
    Optional<TrainingAttempt> findFirstByTaskRecordIdAndStatusInOrderByAttemptNoDesc(
            UUID taskRecordId, java.util.Collection<String> statuses);

    /** 批量取多条的尝试历史（按进度行，命中 idx_tat_record） */
    List<TrainingAttempt> findByTaskRecordIdInOrderByAttemptNoDesc(List<UUID> taskRecordIds);

    /**
     * 该进度行是否已有「已提交但未出结论」的尝试。
     *
     * <p>提交后到出结论之间不允许再开新尝试，否则同一任务会出现两份并行证据，
     * 复核与补考次数都会算不清。</p>
     */
    boolean existsByTaskRecordIdAndStatusIn(UUID taskRecordId, java.util.Collection<String> statuses);

    /**
     * 锁定可领取的尝试（提交后未评分，或评分中但租约已过期）。
     *
     * <p>调用方必须在事务内紧接着调用 {@link #claim}，否则锁在事务结束即释放。</p>
     */
    @Query(value = "SELECT id FROM training_attempt "
            + "WHERE status IN ('submitted', 'grading') "
            + "AND (lease_expires_at IS NULL OR lease_expires_at < now()) "
            // 可领取行的 submitted_at 一定有值；用普通升序才能命中 (submitted_at) 部分索引，
            // NULLS FIRST 会强制排序，积压越多越慢
            + "ORDER BY submitted_at, id LIMIT :limit "
            + "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<UUID> lockClaimableIds(@Param("limit") int limit);

    /** 原子领取：置 grading + 写租约；仅当行仍可领取时生效 */
    @Modifying
    @Query(value = "UPDATE training_attempt SET status = 'grading', lease_owner = :owner, "
            + "lease_expires_at = now() + make_interval(secs => CAST(:leaseSeconds AS double precision)), "
            + "heartbeat_at = now(), grading_attempts = grading_attempts + 1, updated_at = now() "
            + "WHERE id IN (:ids) AND status IN ('submitted', 'grading') "
            + "AND (lease_expires_at IS NULL OR lease_expires_at < now())", nativeQuery = true)
    int claim(@Param("ids") List<UUID> ids,
              @Param("owner") String owner,
              @Param("leaseSeconds") int leaseSeconds);

    /** 心跳续租：只续自己的租约 */
    @Modifying
    @Query(value = "UPDATE training_attempt SET "
            + "lease_expires_at = now() + make_interval(secs => CAST(:leaseSeconds AS double precision)), "
            + "heartbeat_at = now(), updated_at = now() "
            + "WHERE status = 'grading' AND lease_owner = :owner", nativeQuery = true)
    int renewLeases(@Param("owner") String owner, @Param("leaseSeconds") int leaseSeconds);

    /** 释放租约（评分异常/停机时），让其他 worker 可重新领取 */
    @Modifying
    @Query(value = "UPDATE training_attempt SET lease_owner = NULL, lease_expires_at = NULL, updated_at = now() "
            + "WHERE id IN (:ids) AND lease_owner = :owner", nativeQuery = true)
    int releaseLeases(@Param("ids") List<UUID> ids, @Param("owner") String owner);

    /**
     * 到期待处理的进行中尝试（逾期扫描器用）。
     *
     * <p>命中部分索引 {@code idx_tat_deadline}，只扫 in_progress 且 deadline 已过的行。</p>
     */
    @Query(value = "SELECT a.id FROM training_attempt a "
            + "JOIN training_assignment ta ON ta.id = a.assignment_id "
            + "WHERE a.status = 'in_progress' AND a.deadline IS NOT NULL AND a.deadline < now() "
            + "AND ta.status IN ('not_started', 'in_progress', 'pending_certification') "
            + "ORDER BY a.deadline LIMIT :limit", nativeQuery = true)
    List<UUID> findOverdueIds(@Param("limit") int limit);

    /**
     * 陪练没有 attempt.deadline，逾期以分配截止为准。
     *
     * <p>不处理这类行，`timeout_fail` 的陪练会永久卡在 in_progress。
     * 已取消/已认证的分配不再产生逾期副作用。</p>
     */
    @Query(value = "SELECT a.id FROM training_attempt a "
            + "JOIN training_assignment ta ON ta.id = a.assignment_id "
            + "WHERE a.status = 'in_progress' AND a.deadline IS NULL "
            + "AND ta.deadline IS NOT NULL AND ta.deadline < now() "
            + "AND ta.status IN ('not_started', 'in_progress', 'pending_certification') "
            + "ORDER BY ta.deadline LIMIT :limit", nativeQuery = true)
    List<UUID> findOverduePracticeIds(@Param("limit") int limit);

    /**
     * 逾期判失败（timeout_fail）：in_progress → pending_review，建议结论 fail。
     *
     * <p>不直接定稿：超时结论必须给主管一次裁决机会（可申诉），否则学员没有任何出路。</p>
     */
    @Modifying
    @Query(value = "UPDATE training_attempt SET status = 'pending_review', result = 'fail', "
            + "submitted_at = COALESCE(submitted_at, now()), finished_at = now(), "
            + "termination_reason = 'timeout_fail', error_message = NULL, updated_at = now() "
            + "WHERE id = :id AND status = 'in_progress' "
            // 考试截止以行内 deadline 为准：重判时若已被延期则条件不成立，避免按旧截止判失败
            + "AND (deadline IS NULL OR deadline <= now())", nativeQuery = true)
    int markOverdueReview(@Param("id") UUID id);

    /** 逾期代交：in_progress → submitted（仅 auto_submit 策略），并标记来源 */
    @Modifying
    @Query(value = "UPDATE training_attempt SET status = 'submitted', "
            + "submitted_at = COALESCE(submitted_at, now()), termination_reason = 'auto_submit', "
            + "updated_at = now() WHERE id = :id AND status = 'in_progress' "
            + "AND (deadline IS NULL OR deadline <= now())", nativeQuery = true)
    int submitOverdue(@Param("id") UUID id);

    /**
     * 扫描器无法处理的坏行：置 error 并记录原因，移出逾期队列。
     *
     * <p>否则任务/记录缺失的行会永远排在最前，占满每轮 LIMIT 名额。</p>
     */
    @Modifying
    @Query(value = "UPDATE training_attempt SET status = 'error', error_message = :reason, "
            + "termination_reason = 'system_error', finished_at = now(), updated_at = now() "
            + "WHERE id = :id AND status = 'in_progress'", nativeQuery = true)
    int markSweepError(@Param("id") UUID id, @Param("reason") String reason);

    /** 提交：in_progress → submitted（幂等，重复提交返回 0） */
    @Modifying
    @Query("UPDATE TrainingAttempt a SET a.status = :toStatus, a.submittedAt = :at "
            + "WHERE a.id = :id AND a.status = :fromStatus")
    int submit(@Param("id") UUID id,
               @Param("fromStatus") String fromStatus,
               @Param("toStatus") String toStatus,
               @Param("at") LocalDateTime at);

    /** 草稿保存：只有 in_progress 可写，返回 0 表示已锁定 */
    @Modifying
    @Query("UPDATE TrainingAttempt a SET a.answers = :answers WHERE a.id = :id AND a.status = 'in_progress'")
    int saveAnswers(@Param("id") UUID id, @Param("answers") String answers);

    /**
     * 评分完成：落状态/结论/评分并清租约。
     *
     * <p><b>必须带 owner：</b>租约过期被其他 worker 接管后，原 worker 的陈旧结果不能落库；
     * 只判 status 无法阻止这种情况（status 仍是 grading）。</p>
     */
    @Modifying
    @Query("UPDATE TrainingAttempt a SET a.status = :status, a.result = :result, a.score = :score, "
            + "a.errorMessage = NULL, a.finishedAt = :finishedAt, "
            + "a.leaseOwner = NULL, a.leaseExpiresAt = NULL "
            + "WHERE a.id = :id AND a.status = :fromStatus AND a.leaseOwner = :owner")
    int completeGrading(@Param("id") UUID id,
                        @Param("fromStatus") String fromStatus,
                        @Param("owner") String owner,
                        @Param("status") String status,
                        @Param("result") String result,
                        @Param("score") String score,
                        @Param("finishedAt") LocalDateTime finishedAt);

    /** 评分失败但可重试：退回 submitted 等待下一轮领取（不产生业务结论） */
    @Modifying
    @Query("UPDATE TrainingAttempt a SET a.status = :toStatus, a.errorMessage = :errorMessage, "
            + "a.leaseOwner = NULL, a.leaseExpiresAt = NULL "
            + "WHERE a.id = :id AND a.status = :fromStatus AND a.leaseOwner = :owner")
    int requeue(@Param("id") UUID id,
                @Param("fromStatus") String fromStatus,
                @Param("owner") String owner,
                @Param("toStatus") String toStatus,
                @Param("errorMessage") String errorMessage);

    /** 评分失败：落 error + 原因并清租约（不产生业务结论） */
    @Modifying
    @Query("UPDATE TrainingAttempt a SET a.status = :status, a.errorMessage = :errorMessage, "
            + "a.finishedAt = :finishedAt, a.leaseOwner = NULL, a.leaseExpiresAt = NULL "
            + "WHERE a.id = :id AND a.status = :fromStatus AND a.leaseOwner = :owner")
    int markError(@Param("id") UUID id,
                  @Param("fromStatus") String fromStatus,
                  @Param("owner") String owner,
                  @Param("status") String status,
                  @Param("errorMessage") String errorMessage,
                  @Param("finishedAt") LocalDateTime finishedAt);

    /**
     * 运维重跑评分：error → submitted，并重置重试计数。
     *
     * <p>仅用于评分服务恢复后重跑同一份证据；进行中/已定稿的尝试不允许重置。</p>
     */
    @Modifying
    @Query("UPDATE TrainingAttempt a SET a.status = 'submitted', a.errorMessage = NULL, "
            + "a.terminationReason = NULL, "
            + "a.gradingAttempts = 0, a.leaseOwner = NULL, a.leaseExpiresAt = NULL, a.finishedAt = NULL "
            + "WHERE a.id = :id AND a.status = 'error'")
    int resetForRegrade(@Param("id") UUID id);

    /** 主管复核结论：pending_review → finalized，可覆盖 result */
    @Modifying
    @Query("UPDATE TrainingAttempt a SET a.status = :toStatus, a.result = :result, a.finishedAt = :at "
            + "WHERE a.id = :id AND a.status = :fromStatus")
    int finalizeByReview(@Param("id") UUID id,
                         @Param("fromStatus") String fromStatus,
                         @Param("toStatus") String toStatus,
                         @Param("result") String result,
                         @Param("at") LocalDateTime at);

    /**
     * 批次内按场景的平均分（AI 培训效果视图）。
     *
     * <p>只取已出结论的尝试（finalized/pending_review），分数来自 score JSONB；
     * 复核改分在 result 页展示，这里保持 AI 原始分口径，避免两套口径混用。</p>
     */
    @Query(value = """
            SELECT COALESCE(t.scene_tag, '未分类') AS scene,
                   ROUND(AVG((a.score ->> 'total_score')::numeric), 1) AS avg_score,
                   COUNT(*) AS attempts
              FROM training_attempt a
              JOIN training_task t ON t.id = a.task_id
              JOIN training_assignment ta ON ta.id = a.assignment_id
             WHERE ta.cohort_id = :cohortId
               AND a.status IN ('finalized', 'pending_review')
               AND a.score ->> 'total_score' IS NOT NULL
             GROUP BY 1
             ORDER BY 3 DESC
            """, nativeQuery = true)
    List<Object[]> avgScoreByScene(@Param("cohortId") UUID cohortId);

    /** 批次内按尝试状态计数（AI 低置信转人工率） */
    @Query(value = "SELECT a.status, COUNT(*) FROM training_attempt a "
            + "JOIN training_assignment ta ON ta.id = a.assignment_id "
            + "WHERE ta.cohort_id = :cohortId GROUP BY a.status", nativeQuery = true)
    List<Object[]> countByCohortGroupByStatus(@Param("cohortId") UUID cohortId);
}
