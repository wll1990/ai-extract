package com.aiextract.repository;

import com.aiextract.model.TrainingAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 学员分配数据访问接口。
 *
 * <p>计数的增量更新必须走 {@link #addCounters}：调用方先读旧状态算 delta，
 * 再在同一事务内原子累加，禁止无条件 +1（重考会重复计数并撞 ck_ta_counters）。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface TrainingAssignmentRepository extends JpaRepository<TrainingAssignment, UUID> {

    /** 企业隔离读取 */
    Optional<TrainingAssignment> findByIdAndCompanyId(UUID id, UUID companyId);

    /** 评分 Worker 用：按 attempt 反查所属企业，给 LLM 调用设置企业上下文 */
    @Query("SELECT a.companyId FROM TrainingAssignment a WHERE a.id = "
            + "(SELECT t.assignmentId FROM TrainingAttempt t WHERE t.id = :attemptId)")
    UUID findCompanyIdByAttemptId(@Param("attemptId") UUID attemptId);

    /**
     * 行锁读取分配：认证确认必须串行化。
     *
     * <p>两个并发 certify 若都读到「还没有认证记录」，会争用 uq_tcf_assignment，
     * 后一个变成 500；行锁让第二个请求看到已认证状态并按幂等返回。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM TrainingAssignment a WHERE a.id = :id")
    Optional<TrainingAssignment> findByIdForUpdate(@Param("id") UUID id);

    /** 同批次同学生唯一（uq_ta_cohort_learner），批量分配时用于跳过重复 */
    Optional<TrainingAssignment> findByCohortIdAndLearnerId(UUID cohortId, UUID learnerId);

    /** 我的培训：本人分配，倒序（命中 idx_ta_company_learner） */
    Page<TrainingAssignment> findByLearnerIdAndCompanyIdOrderByCreatedAtDesc(
            UUID learnerId, UUID companyId, Pageable pageable);

    /** 批次内全部学员，按分页的稳定顺序返回（兼容旧调用） */
    List<TrainingAssignment> findByCohortIdOrderByCreatedAtAsc(UUID cohortId);

    /** 导师工作台：一次取多个批次的分配，避免逐批次查询 */
    List<TrainingAssignment> findByCohortIdInOrderByCreatedAtAsc(java.util.Collection<UUID> cohortIds);

    /**
     * 批次成员分页。
     *
     * <p>必须带 id 兜底：批量分配时整批共用同一个 created_at，
     * 只按 created_at 排序时 PostgreSQL 不保证同值行的跨页顺序，会重复/漏行。</p>
     */
    Page<TrainingAssignment> findByCohortIdOrderByCreatedAtAscIdAsc(UUID cohortId, Pageable pageable);

    /** 批次成员：状态 + 姓名/账号关键词（服务端分页） */
    @Query(value = """
            SELECT a.* FROM training_assignment a JOIN "user" u ON u.id = a.learner_id
             WHERE a.cohort_id = :cohortId
               AND (CAST(:status AS text) IS NULL OR a.status = CAST(:status AS text))
               AND (CAST(:keyword AS text) IS NULL
                    OR u.name ILIKE '%' || CAST(:keyword AS text) || '%'
                    OR u.account ILIKE '%' || CAST(:keyword AS text) || '%')
             ORDER BY a.created_at, a.id
            """, countQuery = """
            SELECT count(*) FROM training_assignment a JOIN "user" u ON u.id = a.learner_id
             WHERE a.cohort_id = :cohortId
               AND (CAST(:status AS text) IS NULL OR a.status = CAST(:status AS text))
               AND (CAST(:keyword AS text) IS NULL
                    OR u.name ILIKE '%' || CAST(:keyword AS text) || '%'
                    OR u.account ILIKE '%' || CAST(:keyword AS text) || '%')
            """, nativeQuery = true)
    Page<TrainingAssignment> searchCohortMembers(@Param("cohortId") UUID cohortId,
                                                 @Param("status") String status,
                                                 @Param("keyword") String keyword,
                                                 Pageable pageable);

    /** 批次汇总：[total, not_started, in_progress, pending_certification, certified, cancelled] */
    @Query(value = """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE status = 'not_started') AS not_started,
                   count(*) FILTER (WHERE status = 'in_progress') AS in_progress,
                   count(*) FILTER (WHERE status = 'pending_certification') AS pending_certification,
                   count(*) FILTER (WHERE status = 'certified') AS certified,
                   count(*) FILTER (WHERE status = 'cancelled') AS cancelled
              FROM training_assignment WHERE cohort_id = :cohortId
            """, nativeQuery = true)
    List<Object[]> cohortStatusCounts(@Param("cohortId") UUID cohortId);

    /** 批次逾期人数（只算未结束的分配） */
    @Query(value = "SELECT count(*) FROM training_assignment WHERE cohort_id = :cohortId "
            + "AND deadline IS NOT NULL AND deadline < now() "
            + "AND status IN ('not_started','in_progress')", nativeQuery = true)
    long countOverdueByCohort(@Param("cohortId") UUID cohortId);

    /** 批次待复核学员数（去重分配） */
    @Query(value = "SELECT count(DISTINCT a.id) FROM training_assignment a "
            + "JOIN training_task_record r ON r.assignment_id = a.id "
            + "WHERE a.cohort_id = :cohortId AND r.status = 'pending_review'", nativeQuery = true)
    long countPendingReviewByCohort(@Param("cohortId") UUID cohortId);

    /** 待复核超过阈值的学员数 */
    @Query(value = "SELECT count(DISTINCT a.id) FROM training_assignment a "
            + "JOIN training_task_record r ON r.assignment_id = a.id "
            + "JOIN training_attempt t ON t.task_record_id = r.id "
            + "AND t.status IN ('pending_review', 'error') "
            + "WHERE a.cohort_id = :cohortId "
            + "AND COALESCE(t.submitted_at, t.finished_at, t.created_at) < :before", nativeQuery = true)
    long countStalePendingReviewByCohort(@Param("cohortId") UUID cohortId,
                                         @Param("before") java.time.LocalDateTime before);

    /** 批次内学员数 */
    long countByCohortId(UUID cohortId);

    /** 批量分配后一次性取回本批次已存在的学员 ID（避免逐人查库） */
    @Query("SELECT a.learnerId FROM TrainingAssignment a WHERE a.cohortId = :cohortId AND a.learnerId IN :learnerIds")
    List<UUID> findExistingLearnerIds(@Param("cohortId") UUID cohortId,
                                      @Param("learnerIds") List<UUID> learnerIds);

    /** 计数原子累加（delta 由调用方按状态迁移计算，0 表示该维度不变） */
    @Modifying
    @Query("UPDATE TrainingAssignment a SET "
            + "a.completedTasks = a.completedTasks + :completedDelta, "
            + "a.passedTasks = a.passedTasks + :passedDelta, "
            + "a.requiredPassed = a.requiredPassed + :requiredPassedDelta "
            + "WHERE a.id = :id")
    int addCounters(@Param("id") UUID id,
                    @Param("completedDelta") int completedDelta,
                    @Param("passedDelta") int passedDelta,
                    @Param("requiredPassedDelta") int requiredPassedDelta);

    /** 状态推进（只有仍处于 fromStatus 时才生效，防并发覆盖） */
    @Modifying
    @Query("UPDATE TrainingAssignment a SET a.status = :toStatus, a.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE a.id = :id AND a.status = :fromStatus")
    int updateStatus(@Param("id") UUID id,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus);

    /**
     * 取消分配：带状态守卫，绝不覆盖并发认证的结果。
     *
     * <p>只有未结束的分配可取消；返回 0 表示已被认证/已取消。</p>
     */
    @Modifying
    @Query("UPDATE TrainingAssignment a SET a.status = 'cancelled', "
            + "a.updatedAt = CURRENT_TIMESTAMP WHERE a.id = :id "
            + "AND a.status IN ('not_started', 'in_progress', 'pending_certification')")
    int cancelIfOpen(@Param("id") UUID id);

    /** 绝对写回计数（补训轮终态时重算，替代 delta 累加） */
    @Modifying
    @Query("UPDATE TrainingAssignment a SET a.completedTasks = :completed, "
            + "a.passedTasks = :passed, a.requiredPassed = :requiredPassed, "
            + "a.updatedAt = CURRENT_TIMESTAMP WHERE a.id = :id")
    int setCounters(@Param("id") UUID id,
                    @Param("completed") int completed,
                    @Param("passed") int passed,
                    @Param("requiredPassed") int requiredPassed);

    /** 延期：首次延期保留原截止，后续只更新当前值与最近一次延期信息 */
    @Modifying
    @Query("UPDATE TrainingAssignment a SET a.deadline = :deadline, "
            + "a.originalDeadline = COALESCE(a.originalDeadline, :oldDeadline), "
            + "a.deadlineExtendedAt = CURRENT_TIMESTAMP, a.deadlineExtendedBy = :by, "
            + "a.deadlineExtendReason = :reason, a.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE a.id = :id")
    int extendDeadline(@Param("id") UUID id,
                       @Param("deadline") java.time.LocalDateTime deadline,
                       @Param("oldDeadline") java.time.LocalDateTime oldDeadline,
                       @Param("by") UUID by,
                       @Param("reason") String reason);

    /** 撤销认证：certified → pending_certification（并发守卫） */
    @Modifying
    @Query("UPDATE TrainingAssignment a SET a.status = 'pending_certification', "
            + "a.updatedAt = CURRENT_TIMESTAMP WHERE a.id = :id AND a.status = 'certified'")
    int revokeCertified(@Param("id") UUID id);

    /**
     * 待认证滞留：进入 pending_certification 后超过阈值仍未确认的分配。
     *
     * <p>按 updatedAt 从老到新取，避免永远只扫到最早的几条；调用方分页轮转。</p>
     */
    @Query(value = "SELECT id FROM training_assignment "
            + "WHERE status = 'pending_certification' AND updated_at < :before "
            + "ORDER BY updated_at ASC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<UUID> findStalePendingCertificationIds(@Param("before") java.time.LocalDateTime before,
                                                @Param("limit") int limit,
                                                @Param("offset") int offset);

    /** 从任意进行中状态推进到 pending_certification（必修全过时调用） */
    @Modifying
    @Query("UPDATE TrainingAssignment a SET a.status = :toStatus "
            + "WHERE a.id = :id AND a.status IN ('not_started', 'in_progress')")
    int markPendingCertification(@Param("id") UUID id, @Param("toStatus") String toStatus);
}
