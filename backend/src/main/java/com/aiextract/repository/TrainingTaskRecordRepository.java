package com.aiextract.repository;

import com.aiextract.model.TrainingTaskRecord;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 学员任务进度数据访问接口。
 *
 * @author AI Extract Team
 * @since 2026-10-28
 */
@Repository
public interface TrainingTaskRecordRepository extends JpaRepository<TrainingTaskRecord, UUID> {

    /** 一次取回分配的整棵任务进度（我的培训、认证判定都走这条；含全部补训轮） */
    List<TrainingTaskRecord> findByAssignmentIdOrderByCreatedAtAsc(UUID assignmentId);

    /** 按任务+轮次排序（有效轮次折叠用） */
    List<TrainingTaskRecord> findByAssignmentIdOrderByTaskIdAscRoundNoAsc(UUID assignmentId);

    /** 某任务的最新一轮（补训追加前校验） */
    Optional<TrainingTaskRecord> findFirstByAssignmentIdAndTaskIdOrderByRoundNoDesc(UUID assignmentId, UUID taskId);

    /** 当前最大轮次；无记录返回 0 */
    @Query("SELECT COALESCE(MAX(r.roundNo), 0) FROM TrainingTaskRecord r "
            + "WHERE r.assignmentId = :assignmentId AND r.taskId = :taskId")
    int findMaxRoundNo(@Param("assignmentId") UUID assignmentId, @Param("taskId") UUID taskId);

    /** 归属校验：进度行必须属于该分配 */
    Optional<TrainingTaskRecord> findByIdAndAssignmentId(UUID id, UUID assignmentId);

    /**
     * 行锁读取进度行 —— 开始尝试前用它把同一任务的并发请求串行化。
     *
     * <p>不加锁时两个请求会同时算出同一个 attempt_no，唯一约束要等事务提交才报错，
     * 异常落在 catch 之外，前端收到的是 500 而不是「已存在进行中尝试」。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM TrainingTaskRecord r WHERE r.id = :id")
    Optional<TrainingTaskRecord> findByIdForUpdate(@Param("id") UUID id);

    /** 批次内处于某状态的学员数（去重分配，复核队列用） */
    @Query("SELECT COUNT(DISTINCT r.assignmentId) FROM TrainingTaskRecord r "
            + "WHERE r.assignmentId IN :assignmentIds AND r.status = :status")
    long countLearnersByAssignmentIdInAndStatus(@Param("assignmentIds") List<UUID> assignmentIds,
                                                @Param("status") String status);

    /** 批量预取多个分配的进度行（批次看板避免 N+1） */
    List<TrainingTaskRecord> findByAssignmentIdIn(List<UUID> assignmentIds);

    /** 某状态的任务数（必修计数校验、进度聚合） */
    long countByAssignmentIdAndStatus(UUID assignmentId, String status);

    /** 前置任务是否已通过（开始任务前校验） */
    long countByAssignmentIdAndTaskIdInAndStatus(UUID assignmentId, List<UUID> taskIds, String status);

    /**
     * 状态推进（带旧状态守卫，防并发覆盖）。
     *
     * <p>返回 0 表示状态已被其他请求改过，调用方应重读后决定，不能假定成功。</p>
     */
    @Modifying
    @Query("UPDATE TrainingTaskRecord r SET r.status = :toStatus, r.finishedAt = :finishedAt "
            + "WHERE r.id = :id AND r.status = :fromStatus")
    int updateStatus(@Param("id") UUID id,
                     @Param("fromStatus") String fromStatus,
                     @Param("toStatus") String toStatus,
                     @Param("finishedAt") LocalDateTime finishedAt);

    /** 阅读确认：置 passed + 记录确认时间（仅阅读任务由 Service 调用） */
    @Modifying
    @Query("UPDATE TrainingTaskRecord r SET r.status = :toStatus, r.acknowledgedAt = :ackAt, "
            + "r.startedAt = COALESCE(r.startedAt, :ackAt), r.finishedAt = :ackAt "
            + "WHERE r.id = :id AND r.status IN ('available', 'in_progress')")
    int acknowledge(@Param("id") UUID id,
                    @Param("toStatus") String toStatus,
                    @Param("ackAt") LocalDateTime ackAt);

    /** 开始：available → in_progress，记录开始时间 */
    @Modifying
    @Query("UPDATE TrainingTaskRecord r SET r.status = :toStatus, r.startedAt = :startedAt "
            + "WHERE r.id = :id AND r.status = :fromStatus")
    int markStarted(@Param("id") UUID id,
                    @Param("fromStatus") String fromStatus,
                    @Param("toStatus") String toStatus,
                    @Param("startedAt") LocalDateTime startedAt);

    /** 评分需人工复核：可作答状态 → pending_review（并发安全，已终结的行不动） */
    @Modifying
    @Query("UPDATE TrainingTaskRecord r SET r.status = 'pending_review' "
            + "WHERE r.id = :id AND r.status IN ('available', 'in_progress')")
    int markPendingReview(@Param("id") UUID id);

    /** 前置全部通过后解锁：locked → available（并发安全，只有仍锁定时生效） */
    @Modifying
    @Query("UPDATE TrainingTaskRecord r SET r.status = 'available' "
            + "WHERE r.id = :id AND r.status = 'locked'")
    int unlock(@Param("id") UUID id);

    /**
     * 企业内失败任务按场景聚合 —— AI 培训需求的信号来源之一。
     *
     * <p>只统计最新轮失败（补训通过后不再算缺口），scene_tag 为空的任务不参与聚合。</p>
     */
    @Query(value = """
            SELECT t.scene_tag AS scene, COUNT(*) AS failed
              FROM training_task_record r
              JOIN training_task t ON t.id = r.task_id
              JOIN training_assignment a ON a.id = r.assignment_id
             WHERE a.company_id = :companyId
               AND r.status = 'failed'
               AND t.scene_tag IS NOT NULL AND t.scene_tag <> ''
               AND r.round_no = (SELECT MAX(x.round_no) FROM training_task_record x
                                  WHERE x.assignment_id = r.assignment_id AND x.task_id = r.task_id)
             GROUP BY t.scene_tag
             ORDER BY COUNT(*) DESC
             LIMIT 20
            """, nativeQuery = true)
    List<Object[]> countFailedByScene(@Param("companyId") UUID companyId);

    /**
     * 每日微练提醒：未完成的 drill 进度行（分配仍未结束）。
     *
     * <p>只取 available/in_progress，已通过/待复核的不再催。</p>
     */
    @Query(value = """
            SELECT r.id
              FROM training_task_record r
              JOIN training_task t ON t.id = r.task_id
              JOIN training_assignment a ON a.id = r.assignment_id
             WHERE t.type = 'drill'
               AND r.status IN ('available', 'in_progress')
               AND a.status IN ('not_started', 'in_progress')
             ORDER BY r.updated_at ASC
             LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<UUID> findPendingDrillRecordIds(@Param("limit") int limit, @Param("offset") int offset);

    /** 批次内补训轮次数（AI 培训效果视图：补训后通过情况） */
    @Query("SELECT COUNT(r) FROM TrainingTaskRecord r WHERE r.assignmentId IN "
            + "(SELECT a.id FROM TrainingAssignment a WHERE a.cohortId = :cohortId) AND r.roundNo > 1")
    long countRemediationRoundsByCohort(@Param("cohortId") UUID cohortId);
}
