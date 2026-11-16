package com.aiextract.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 培训导出查询 —— 一行 = 学员 × 任务 × 轮次，供 CSV worker 直接写盘。
 *
 * <p>SQL 只允许出现在 Repository；Service/Worker 不拼 SQL。
 * 认证取「confirmed 优先，否则最新一条」；尝试取最新一条。</p>
 *
 * @author AI Extract Team
 * @since 2026-10-29
 */
@Repository
public interface TrainingExportRepository extends JpaRepository<com.aiextract.model.ExportJob, UUID> {

    @Query(value = """
            SELECT c.name            AS cohort_name,
                   p.name            AS plan_name,
                   u.name            AS learner_name,
                   u.account         AS learner_account,
                   a.status          AS assignment_status,
                   a.deadline        AS deadline,
                   (a.deadline IS NOT NULL AND a.deadline < now()
                        AND a.status IN ('not_started','in_progress')) AS overdue,
                   t.phase_no        AS phase_no,
                   t.phase_name      AS phase_name,
                   t.title           AS task_title,
                   t.type            AS task_type,
                   t.is_required     AS is_required,
                   r.round_no        AS round_no,
                   r.status          AS record_status,
                   r.remediation_reason AS remediation_reason,
                   (SELECT count(*) FROM training_attempt x WHERE x.task_record_id = r.id) AS attempt_count,
                   la.result         AS latest_result,
                   -- 复核改过分时以人工结论为准，避免导出的「最新总分」与看板不一致
                   COALESCE(lr.adjusted_score ->> 'total_score',
                            la.score ->> 'total_score') AS latest_score,
                   la.submitted_at   AS submitted_at,
                   cert.status       AS cert_status,
                   cu.name           AS confirmed_by_name,
                   cert.confirmed_at AS confirmed_at,
                   ru.name           AS revoked_by_name,
                   cert.revoked_at   AS revoked_at,
                   cert.revoke_reason AS revoke_reason
              FROM training_assignment a
              JOIN training_cohort c ON c.id = a.cohort_id
              JOIN training_plan p ON p.id = c.plan_id
              JOIN "user" u ON u.id = a.learner_id
              JOIN training_task_record r ON r.assignment_id = a.id
              JOIN training_task t ON t.id = r.task_id
                   AND t.plan_id = c.plan_id AND t.version = c.plan_version
              LEFT JOIN LATERAL (
                   SELECT * FROM training_attempt x WHERE x.task_record_id = r.id
                    ORDER BY x.attempt_no DESC LIMIT 1
              ) la ON true
              LEFT JOIN LATERAL (
                   SELECT * FROM training_review x WHERE x.attempt_id = la.id
                    ORDER BY x.created_at DESC LIMIT 1
              ) lr ON true
              LEFT JOIN LATERAL (
                   SELECT * FROM training_certification x WHERE x.assignment_id = a.id
                    ORDER BY (x.status = 'confirmed') DESC, x.created_at DESC LIMIT 1
              ) cert ON true
              LEFT JOIN "user" cu ON cu.id = cert.confirmed_by
              LEFT JOIN "user" ru ON ru.id = cert.revoked_by
             WHERE a.cohort_id = :cohortId
             ORDER BY u.name, t.phase_no, t.order_no, r.round_no
             LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findCohortExportRows(@Param("cohortId") UUID cohortId, @Param("limit") int limit);
}
